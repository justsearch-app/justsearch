#!/usr/bin/env node
// The desktop window refuses style attributes set from script and <style> elements made at runtime (Tauri adds a nonce to style-src). Values go through Lit styleMap or the CSSOM; runtime sheets through adoptedStyleSheets.
// No baseline: no style attribute in Lit templates; runtime styles and sheets must use CSP-safe APIs.
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
      const parts = [node.template.head.text];
      for (const span of node.template.templateSpans) parts.push('\u0000', span.literal.text);
      const html = parts.join('').replace(/<!--[\s\S]*?-->/g, '');
      // Scan attribute values only, ignoring text, comments and similarly named attributes.
      for (const tag of html.matchAll(/<([A-Za-z][\w:-]*)\b((?:[^>"']|"[^"]*"|'[^']*')*)>/g)) {
        for (const attr of tag[2].matchAll(/(?:^|\s)([^\s=]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g)) {
          const value = attr[2] ?? attr[3] ?? attr[4];
          if (attr[1].toLowerCase() !== 'style') continue;
          // Lit writes any style attribute binding with setAttribute, styleMap included (its first
          // render returns a string). Chromium (WebView2 on Windows) also refuses literal style
          // attributes when Lit imports its template. Use the component's CSS, or bind the
          // property: .style=${text} assigns through the CSSOM.
          report(node, value.includes('\u0000') ? 'bound-style-attribute' : 'literal-style-attribute');
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
