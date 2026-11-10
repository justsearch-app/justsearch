// SPDX-License-Identifier: Apache-2.0
// @vitest-environment node
import { ESLint } from 'eslint';
import { expect, it } from 'vitest';

it('rejects planted Date locale calls while allowing number formatting', async () => {
  const eslint = new ESLint();
  const [result] = await eslint.lintText(`
    export function dates(date: Date, optional?: Date) {
      date.toLocaleString();
      date.toLocaleDateString();
      date.toLocaleTimeString();
      new Date().toLocaleString();
      optional?.toLocaleTimeString();
      date['toLocaleDateString']();
      return (1234).toLocaleString();
    }
  `, { filePath: 'src/shell-v0/display/format.ts' });
  expect(result?.messages.filter(m => m.ruleId === 'ses/no-date-locale').map(m => m.line))
    .toEqual([3, 4, 5, 6, 7, 8]);
  const [javascript] = await eslint.lintText(`
    const date = new Date();
    date.toLocaleString();
    new Date().toLocaleTimeString();
    (1234).toLocaleString();
  `, { filePath: 'src/main.jsx' });
  expect(javascript?.messages.filter(m => m.ruleId === 'ses/no-date-locale').map(m => m.line))
    .toEqual([3, 4]);
});
