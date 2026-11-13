#!/usr/bin/env node
// The desktop window refuses style attributes set from script and <style> elements made at runtime (Tauri adds a nonce to style-src). Values go through Lit styleMap or the CSSOM; runtime sheets through adoptedStyleSheets.
// No baseline: literal attributes are allowed; runtime attributes/sheets must use CSP-safe APIs.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
const root = path.resolve(process.env.JS_REPO_ROOT || process.cwd());
const require = createRequire(path.join(root, 'modules/ui-web/package.json'));
const ts = require(process.env.CSP_CHECK_TYPESCRIPT || 'typescript');
export function scanSource(source, filename = 'fixture.ts') {
  const ast = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true);
  const hits = [];
  const report = (node, kind, offset = node.getStart(ast)) => {
    const { line, character } = ast.getLineAndCharacterOfPosition(offset);
    hits.push({ file: filename, line: line + 1, column: character + 1, kind });
  };
  const name = n => ts.isPropertyAccessExpression(n) ? n.name.text
    : ts.isElementAccessExpression(n) && ts.isStringLiteralLike(n.argumentExpression) ? n.argumentExpression.text : '';
  const str = n => n && ts.isStringLiteralLike(n) ? n.text.toLowerCase() : null;
  function visit(node) {
    if (ts.isTaggedTemplateExpression(node) &&
        (ts.isIdentifier(node.tag) && node.tag.text === 'html' || name(node.tag) === 'html') &&
        ts.isTemplateExpression(node.template)) {
      // Replace expressions with a sentinel so quotes and > inside JS do not affect HTML parsing.
      // Each expression becomes \u0000<index>\u0001 so an attribute can be traced to its expression.
      const spans = node.template.templateSpans;
      const parts = [node.template.head.text];
      spans.forEach((span, i) => parts.push(`\u0000${i}\u0001`, span.literal.text));
      const html = parts.join('').replace(/<!--[\s\S]*?-->/g, '');
      // Scan attribute values only, ignoring text, comments and similarly named attributes.
      for (const tag of html.matchAll(/<([A-Za-z][\w:-]*)\b((?:[^>"']|"[^"]*"|'[^']*')*)>/g)) {
        for (const attr of tag[2].matchAll(/(?:^|\s)([^\s=]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g)) {
          const value = attr[2] ?? attr[3] ?? attr[4];
          if (attr[1].toLowerCase() !== 'style' || !value.includes('\u0000')) continue;
          // Allowed: the whole value is one Lit styleMap(...) directive, which writes through the CSSOM.
          const only = value.trim().match(/^\u0000(\d+)\u0001$/);
          const expr = only && spans[Number(only[1])].expression;
          if (expr && ts.isCallExpression(expr) && ts.isIdentifier(expr.expression) && expr.expression.text === 'styleMap') continue;
          report(node, 'bound-style-attribute');
        }
      }
    }
    if (ts.isCallExpression(node)) {
      const method = name(node.expression);
      if ((method === 'setAttribute' && str(node.arguments[0]) === 'style') ||
          (method === 'setAttributeNS' && str(node.arguments[1]) === 'style')) report(node, 'set-style-attribute');
      if ((method === 'createElement' && str(node.arguments[0]) === 'style') ||
          (method === 'createElementNS' && str(node.arguments[1]) === 'style')) report(node, 'runtime-style-element');
    }
    ts.forEachChild(node, visit);
  }
  visit(ast);
  return hits;
}
export function scanTree(directory) {
  const hits = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (['node_modules', 'generated', '__fixtures__'].includes(entry.name)) continue;
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) hits.push(...scanTree(full));
    else if (/\.[cm]?[jt]sx?$/.test(entry.name) && !/\.(test|spec)\./.test(entry.name)) {
      hits.push(...scanSource(fs.readFileSync(full, 'utf8'), path.relative(root, full)));
    }
  }
  return hits;
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const hits = scanTree(path.join(root, 'modules/ui-web/src'));
  for (const h of hits) console.error(`${h.file}:${h.line}:${h.column}: ${h.kind}`);
  console.log(`CSP element styles: ${hits.length} forbidden sites`);
  process.exitCode = hits.length ? 1 : 0;
}
