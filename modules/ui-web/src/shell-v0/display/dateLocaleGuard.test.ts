// SPDX-License-Identifier: Apache-2.0
// @vitest-environment node
import { resolve } from 'node:path';
import { ESLint, type Linter } from 'eslint';
import ts from 'typescript';
import { expect, it } from 'vitest';

const typescript = `
    export function dates(date: Date, optional?: Date) {
      date.toLocaleString();
      date.toLocaleDateString();
      date.toLocaleTimeString();
      new Date().toLocaleString();
      optional?.toLocaleTimeString();
      date['toLocaleDateString']();
      return (1234).toLocaleString();
    }
    class DerivedDate extends Date {}
    export function subclass(date: DerivedDate) {
      date.toLocaleString();
    }
    export function generic<T extends Date>(date: T) {
      date.toLocaleDateString();
    }
    export function union(date: Date | number) {
      date.toLocaleString();
    }
    'text'.toLocaleString();
  `;
const javascript = `
    const date = new Date();
    date.toLocaleString();
    new Date().toLocaleTimeString();
    (1234).toLocaleString();
    'text'.toLocaleString();
  `;

// Load the real guard, but avoid the repository's project service: it builds the whole app
// to lint these snippets. This program contains only the planted files and the ES library.
const config: Linter.Config[] = (await import(new URL('../../../eslint.config.js', import.meta.url).href)).default;
const guard = config.find(entry => entry.plugins?.ses);
if (!guard) throw new Error('Missing SES lint guard configuration');
const snippets = new Map([
  [resolve('date-locale-guard.ts'), typescript],
  [resolve('date-locale-guard.jsx'), javascript],
]);
const options: ts.CompilerOptions = { allowJs: true, strict: true, lib: ['lib.es5.d.ts'], types: [] };
const host = ts.createCompilerHost(options);
const getSourceFile = host.getSourceFile;
host.getSourceFile = (fileName, languageVersion, onError, shouldCreateNewSourceFile) => {
  const text = snippets.get(fileName);
  return text === undefined
    ? getSourceFile(fileName, languageVersion, onError, shouldCreateNewSourceFile)
    : ts.createSourceFile(fileName, text, languageVersion);
};
const program = ts.createProgram([...snippets.keys()], options, host);
const eslint = new ESLint({
  overrideConfigFile: true,
  overrideConfig: [{
    files: ['**/*.{ts,jsx}'],
    languageOptions: {
      parser: guard.languageOptions?.parser,
      parserOptions: { programs: [program] },
    },
    plugins: guard.plugins,
    rules: guard.rules,
  }],
});

it('rejects planted Date locale calls while allowing number formatting', async () => {
  const [result] = await eslint.lintText(typescript, { filePath: 'date-locale-guard.ts' });
  expect(result?.messages.map(m => [m.ruleId, m.line]))
    .toEqual([3, 4, 5, 6, 7, 8, 13, 16, 19].map(line => ['ses/no-date-locale', line]));
  const [jsResult] = await eslint.lintText(javascript, { filePath: 'date-locale-guard.jsx' });
  expect(jsResult?.messages.map(m => [m.ruleId, m.line]))
    .toEqual([3, 4].map(line => ['ses/no-date-locale', line]));
});
