// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { isSv3Recovery } from './sv3-recovery.js';

const draft = (preparation: unknown = { kind: 'ask', scope: { kind: 'files', paths: ['/alpha.md'] } }) =>
  ({ place: null, text: 'Keep authored text', preparation });
const payload = (preparation?: unknown) => ({ version: 2, task: null, drafts: [draft(preparation)] });

describe('DS1 session proposal trust boundary', () => {
  it('reads the strict original text-only payload and strict new data-only proposal', () => {
    expect(isSv3Recovery({ task: null, drafts: [{ place: null, text: 'old draft' }] })).toBe(true);
    expect(isSv3Recovery(payload())).toBe(true);
    expect(isSv3Recovery({ task: null, drafts: [draft()] })).toBe(false);
    expect(isSv3Recovery({ ...payload(), version: 3 })).toBe(true);
    expect(isSv3Recovery({ ...payload(), version: 4 })).toBe(false);
  });
  it.each([
    { kind: ['search'] },
    { kind: 'ask', scope: { kind: ['files'], paths: ['id'] } },
    { kind: 'search', scope: { kind: 'files', paths: ['/alpha.md'] } },
    { kind: 'delegate', scope: { kind: 'files', paths: ['/alpha.md'] } },
    { kind: 'model', forceAllowed: true },
    { kind: 'ask', scope: { kind: 'files', paths: [] } },
    { kind: 'ask', scope: { kind: 'files', paths: ['/alpha.md', '/alpha.md'] } },
    { kind: 'ask', scope: { kind: 'files', paths: [' '] } },
    { kind: 'summarize', scope: { kind: 'all' } },
    { kind: 'structured', schema: '{}', scope: { kind: 'all' } },
    { kind: 'ask', scope: { kind: 'files', paths: ['/alpha.md'], excerpt: 'private bytes' } },
    { kind: 'ask', scope: { kind: 'all' }, approved: true },
  ])('rejects unsupported/authority-bearing proposal %j', preparation => {
    expect(isSv3Recovery(payload(preparation))).toBe(false);
  });
  it.each([
    { kind: 'search' }, { kind: 'delegate' }, { kind: 'model' },
    { kind: 'ask', scope: { kind: 'all' } },
    { kind: 'ask', scope: { kind: 'reselect', paths: [] } },
    { kind: 'structured', schema: '{unfinished', scope: { kind: 'typed' } },
    { kind: 'summarize', scope: { kind: 'reselect', paths: ['/alpha.md'] } },
  ])('retains valid data without inventing request approval %j', preparation => {
    expect(isSv3Recovery(payload(preparation))).toBe(true);
  });
  it('keeps two different proposals even at identical text/place, refuses a third or duplicate', () => {
    const one = draft(); const two = draft({ kind: 'model' });
    expect(isSv3Recovery({ ...payload(), drafts: [one, two] })).toBe(true);
    expect(isSv3Recovery({ ...payload(), drafts: [one, two, draft({ kind: 'search' })] })).toBe(false);
    expect(isSv3Recovery({ ...payload(), drafts: [one, one] })).toBe(false);
  });
  it('enforces existing UTF-8, schema and ID bounds without truncation', () => {
    const validPaths = Array.from({ length: 128 }, (_, i) => '/' + i);
    expect(isSv3Recovery(payload({ kind: 'ask', scope: { kind: 'files', paths: validPaths } }))).toBe(true);
    expect(isSv3Recovery(payload({ kind: 'ask', scope: { kind: 'files', paths: [...validPaths, '/extra'] } }))).toBe(false);
    expect(isSv3Recovery(payload({ kind: 'ask', scope: { kind: 'files', paths: ['界'.repeat(1366)] } }))).toBe(false);
    expect(isSv3Recovery(payload({ kind: 'structured', scope: { kind: 'typed' }, schema: '界'.repeat(5462) }))).toBe(false);
    expect(isSv3Recovery({ ...payload(), drafts: [{ ...draft(), text: '界'.repeat(21846) }] })).toBe(false);
  });
});
