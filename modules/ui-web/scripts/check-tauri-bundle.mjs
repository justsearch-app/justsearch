// SPDX-License-Identifier: Apache-2.0
// Inspect shipped JavaScript, including runtime-built specifiers Vite cannot resolve.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import ts from 'typescript';

export function bareTauriSpecifiers(code) {
  const source = ts.createSourceFile('bundle.js', code, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
  const found = new Set();
  function visit(node) {
    if (ts.isStringLiteralLike(node) && node.text.startsWith('@tauri-apps')) {
      let specifier = node.text;
      const array = node.parent;
      if (ts.isArrayLiteralExpression(array) && array.elements.every(ts.isStringLiteralLike)) {
        const access = array.parent;
        const call = access.parent;
        if (ts.isPropertyAccessExpression(access) && access.name.text === 'join'
            && ts.isCallExpression(call) && call.arguments.length === 1
            && ts.isStringLiteralLike(call.arguments[0]) && call.arguments[0].text === '/') {
          specifier = array.elements.map((element) => element.text).join('/');
        }
      }
      // The optional plugin hot-reload path has no plugin-fs dependency;
      // its load failure is caught. This is the only allowed bare specifier.
      if (specifier !== '@tauri-apps/plugin-fs') found.add(specifier);
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
  return [...found];
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const root = resolve(process.argv[2] ?? 'dist');
  const files = readdirSync(root, { recursive: true }).filter((file) => /\.[cm]?js$/.test(file));
  assert.ok(files.length > 0, `No shipped JavaScript found in ${root}`);
  const failures = files.flatMap((file) =>
    bareTauriSpecifiers(readFileSync(resolve(root, file), 'utf8')).map((specifier) => `${file}: ${specifier}`));
  assert.deepEqual(failures, [], 'Bare Tauri module specifiers remain in the production bundle');
  console.log(`Tauri bundle check: PASS (${files.length} JavaScript files; optional plugin-fs excepted)`);
}
