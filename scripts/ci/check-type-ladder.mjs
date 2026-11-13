#!/usr/bin/env node
// No baseline: inspect every possible token definition, including scoped overrides and fallbacks.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
const root = path.resolve(process.env.JS_REPO_ROOT || process.cwd());
const require = createRequire(path.join(root, 'modules/ui-web/package.json'));
const ts = require('typescript');
export const sizes = [11, 12, 13, 15, 20, 28];
export const radii = [0, 4, 8, 14, 9999];
const target = /^(font-size|font|border-radius|border-(?:top-left|top-right|bottom-left|bottom-right|start-start|start-end|end-start|end-end)-radius)$/;
const tokenKind = name => /radius/.test(name) ? 'border-radius' : /(?:font-size|text-size|typography.*size)/.test(name) ? 'font-size' : null;
const unknown = '__UNRESOLVED__';

// Split only outside functions/quotes; CSS var fallbacks may themselves contain commas or spaces.
function split(value, separator) {
  let depth = 0, quote = '', start = 0;
  const out = [];
  for (let i = 0; i < value.length; i++) {
    const c = value[i];
    if (quote) { if (c === quote && value[i - 1] !== '\\') quote = ''; continue; }
    if (c === '"' || c === "'") { quote = c; continue; }
    if (c === '(') depth++;
    if (c === ')') depth--;
    if (!depth && separator.test(c)) { if (i > start) out.push(value.slice(start, i)); start = i + 1; }
  }
  if (start < value.length) out.push(value.slice(start));
  return out.map(s => s.trim()).filter(Boolean);
}

function declarations(text, file, source, offset = 0) {
  // Preserve newlines and offsets while discarding comments.
  text = text.replace(/\/\*[\s\S]*?\*\//g, s => s.replace(/[^\n]/g, ' '));
  const out = [];
  const pattern = /(^|[\s;{\"'`>])(--[\w-]+|font-size|font|border-(?:(?:top-left|top-right|bottom-left|bottom-right|start-start|start-end|end-start|end-end)-)?radius)\s*:/gi;
  for (const m of text.matchAll(pattern)) {
    if (!m[2].startsWith('--')) m[2] = m[2].toLowerCase();
    const start = m.index + m[0].length;
    let end = start, depth = 0, quote = '';
    for (; end < text.length; end++) {
      const c = text[end];
      if (quote) { if (c === quote && text[end - 1] !== '\\') quote = ''; continue; }
      if ((c === '"' || c === "'") && (depth || m[2] === 'font' && /\s/.test(text[end - 1]))) { quote = c; continue; }
      if (c === '(') depth++;
      if (c === ')') depth--;
      if (!depth && /[;}"'`<>]/.test(c)) break;
    }
    const value = text.slice(start, end).trim().replace(/\s*!important\s*$/i, '');
    const index = offset + m.index + m[1].length;
    const before = text.slice(0, m.index);
    const selector = before.slice(before.lastIndexOf('}') + 1).split('{').slice(-2, -1)[0]?.trim();
    out.push({ file, line: source.slice(0, index).split('\n').length, property: m[2], value, selector });
  }
  return out;
}

export function extract(source, file = 'fixture.ts', exports = new Map()) {
  if (/\.(css|html)$/.test(file)) return declarations(source, file, source);
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
  const bindings = new Map();
  function collect(n) {
    if (ts.isImportDeclaration(n) && ts.isStringLiteral(n.moduleSpecifier) && n.importClause?.namedBindings && ts.isNamedImports(n.importClause.namedBindings)) {
      const module = path.posix.normalize(path.posix.join(path.posix.dirname(file), n.moduleSpecifier.text)).replace(/\.js$/, '.ts');
      for (const spec of n.importClause.namedBindings.elements) {
        const initializer = exports.get(module)?.get(spec.propertyName?.text || spec.name.text);
        bindings.set(spec.name.text, bindings.has(spec.name.text) ? null : initializer);
      }
    }
    if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name)) {
      // Without a TS type-checker/scope engine, ambiguous or mutable bindings stay unknown.
      const constant = n.parent.flags & ts.NodeFlags.Const;
      bindings.set(n.name.text, !bindings.has(n.name.text) && constant ? n.initializer : null);
    }
    ts.forEachChild(n, collect);
  }
  collect(ast);
  function value(n, seen = new Set()) {
    if (!n) return unknown;
    if (ts.isStringLiteralLike(n) || ts.isNumericLiteral(n)) return n.text;
    if (ts.isParenthesizedExpression(n) || ts.isAsExpression(n)) return value(n.expression, seen);
    if (ts.isIdentifier(n) && bindings.has(n.text) && !seen.has(n.text)) return value(bindings.get(n.text), new Set([...seen, n.text]));
    if (ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.PlusToken) return value(n.left, seen) + value(n.right, seen);
    if (ts.isTemplateExpression(n)) return n.head.text + n.templateSpans.map(s => value(s.expression, seen) + s.literal.text).join('');
    return unknown;
  }
  const out = [];
  const add = (n, property, v) => out.push({ file, line: ast.getLineAndCharacterOfPosition(n.getStart(ast)).line + 1, property, value: v });
  const name = n => ts.isPropertyAccessExpression(n) ? n.name.text : ts.isElementAccessExpression(n) ? value(n.argumentExpression) : '';
  const styleProperty = name => {
    const p = name.replace(/[A-Z]/g, c => `-${c.toLowerCase()}`);
    return name === 'cssText' ? name : target.test(p) ? p : '';
  };
  function addStyle(n, p, expression, simple = true) {
    const v = simple ? value(expression) : unknown;
    if (p === 'cssText') {
      if (v.includes(unknown)) add(n, 'font-size', v);
      out.push(...declarations(v, file, source, expression.getStart(ast) + 1));
    } else add(n, p, v);
  }
  function visit(n) {
    // Maximal string expressions also catch assembled declarations, without double-counting leaves.
    if (ts.isStringLiteralLike(n) || ts.isTemplateExpression(n) || ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.PlusToken) {
      out.push(...declarations(value(n), file, source, n.getStart(ast) + 1));
      return;
    }
    if (ts.isBinaryExpression(n) && n.operatorToken.kind >= ts.SyntaxKind.FirstAssignment && n.operatorToken.kind <= ts.SyntaxKind.LastAssignment) {
      const p = styleProperty(name(n.left));
      if (p) {
        addStyle(n, p, n.right, n.operatorToken.kind === ts.SyntaxKind.EqualsToken);
        return;
      }
    }
    if (ts.isCallExpression(n) && name(n.expression) === 'assign' && ts.isIdentifier(n.expression.expression) && n.expression.expression.text === 'Object') {
      for (const source of n.arguments.slice(1)) if (ts.isObjectLiteralExpression(source)) {
        for (const entry of source.properties) {
          if (!entry.name) continue;
          const key = ts.isComputedPropertyName(entry.name) ? value(entry.name.expression) : entry.name.text;
          const p = styleProperty(key);
          if (p) addStyle(entry, p, ts.isShorthandPropertyAssignment(entry) ? entry.name : entry.initializer || entry);
        }
      }
    }
    if (ts.isCallExpression(n) && name(n.expression) === 'setProperty') {
      const p = value(n.arguments[0]);
      if (target.test(p) || p.startsWith('--')) { add(n, p, value(n.arguments[1])); return; }
      if (p.includes(unknown)) add(n, 'font-size', unknown); // A dynamic property cannot be proven safe.
    }
    ts.forEachChild(n, visit);
  }
  visit(ast);
  return out;
}

function expand(value, tokens, seen = new Set()) {
  const start = value.indexOf('var(');
  if (start < 0) return [{ value }];
  let depth = 1, end = start + 4;
  for (; end < value.length && depth; end++) { if (value[end] === '(') depth++; if (value[end] === ')') depth--; }
  if (depth) return [{ value, reason: 'unclosed var()' }];
  const body = value.slice(start + 4, end - 1);
  let comma = -1, nesting = 0;
  for (let i = 0; i < body.length; i++) { if (body[i] === '(') nesting++; if (body[i] === ')') nesting--; if (body[i] === ',' && !nesting) { comma = i; break; } }
  const name = (comma < 0 ? body : body.slice(0, comma)).trim();
  const definitions = tokens.get(name) || [];
  if (seen.has(name)) return [{ value, reason: `cyclic var(${name})` }];
  const choices = definitions.map(d => ({ value: d.value, origin: d }));
  if (comma >= 0) choices.push({ value: body.slice(comma + 1).trim() });
  if (!choices.length) return [{ value, reason: `unresolvable var(${name})` }];
  const out = [];
  for (const choice of choices) for (const resolved of expand(choice.value, tokens, new Set([...seen, name]))) {
    if (resolved.reason) out.push({ ...resolved, origin: resolved.origin || choice.origin });
    else for (const rest of expand(value.slice(0, start) + resolved.value + value.slice(end), tokens, seen)) out.push({ ...rest, origin: resolved.origin || choice.origin || rest.origin });
    if (out.length > 2048) return [{ value, reason: 'too many token alternatives to resolve' }];
  }
  return out;
}

function validate(property, value, selector) {
  if (value.includes(unknown)) return { reason: 'unresolved declaration expression' };
  value = value.toLowerCase();
  if (value === 'inherit') return { px: 'inherit' };
  if (/[a-z-]+\(/i.test(value)) return { reason: 'math or unsupported function' };
  let parts;
  if (property === 'font') {
    const words = split(value, /\s/);
    while (words.length && /^(normal|italic|oblique|small-caps|bold|bolder|lighter|[1-9]00)$/.test(words[0])) words.shift();
    if (words.length < 2) return { reason: 'unresolvable font shorthand' };
    parts = [words[0].split('/')[0]];
  } else if (property === 'font-size') parts = [value];
  else {
    const axes = split(value, /\//);
    if (!axes.length || axes.length > (property === 'border-radius' ? 2 : 1)) return { reason: 'invalid radius slash syntax' };
    const tuples = axes.map(a => split(a, /\s/));
    if (tuples.some(a => !a.length || a.length > (property === 'border-radius' ? 4 : 2))) return { reason: 'invalid radius tuple' };
    parts = tuples.flat();
  }
  const px = [];
  for (const part of parts) {
    if (property.includes('radius') && part === '50%') { px.push('50%'); continue; }
    const m = /^([+-]?(?:\d*\.)?\d+)(px|rem)?$/.exec(part);
    if (!m || !m[2] && Number(m[1]) !== 0) return { px: px.join(' '), reason: `unsupported size or keyword: ${part}` };
    const n = Number(m[1]) * (m[2] === 'rem' ? 16 : 1);
    px.push(n);
    // Root is the rem conversion authority, never a text tier.
    const rootSize = property === 'font-size' && /^(html|:root)$/.test(selector || '') && n === 16;
    if (!(property.includes('radius') ? radii : sizes).includes(n) && !rootSize) return { px: px.join(' '), reason: 'outside ladder' };
  }
  return { px: px.join(' ') };
}

export function scanSources(files) {
  // Named imports of exported literal constants are common for CSSOM property names. Resolve only
  // provable constants; expressions, namespace imports and mutable exports remain unknown.
  const exports = new Map();
  for (const { file, source } of files) {
    if (!file.endsWith('.ts')) continue;
    const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true), literals = new Map();
    for (const statement of ast.statements) {
      if (!ts.isVariableStatement(statement) || !(statement.declarationList.flags & ts.NodeFlags.Const) || !statement.modifiers?.some(m => m.kind === ts.SyntaxKind.ExportKeyword)) continue;
      for (const d of statement.declarationList.declarations) if (ts.isIdentifier(d.name) && d.initializer && (ts.isStringLiteralLike(d.initializer) || ts.isNumericLiteral(d.initializer))) literals.set(d.name.text, d.initializer);
    }
    exports.set(path.posix.normalize(file), literals);
  }
  const all = files.flatMap(({ file, source }) => extract(source, file, exports));
  const tokens = new Map();
  for (const d of all) if (d.property.startsWith('--')) tokens.set(d.property, [...tokens.get(d.property) || [], d]);
  const failures = [];
  for (const d of all) {
    const property = target.test(d.property) ? d.property : tokenKind(d.property);
    if (!property) continue;
    for (const resolved of expand(d.value, tokens)) {
      const result = resolved.reason ? { reason: resolved.reason } : validate(property, resolved.value, d.selector);
      if (result.reason) failures.push({ ...d, resolved: resolved.value, px: result.px ?? 'unknown', reason: result.reason,
        ...(resolved.origin ? { definition: `${resolved.origin.file}:${resolved.origin.line}` } : {}) });
    }
  }
  return [...new Map(failures.map(f => [JSON.stringify(f), f])).values()];
}

export function scanTree(repo = root) {
  const files = [];
  function walk(dir) {
    for (const entry of fs.readdirSync(path.join(repo, dir), { withFileTypes: true })) {
      const file = `${dir}/${entry.name}`;
      if (entry.isDirectory() && entry.name !== 'node_modules') walk(file);
      else if (entry.isFile() && /\.(ts|css)$/.test(file) && !/\.(test|spec)\.ts$/.test(file)) files.push(file);
    }
  }
  walk('modules/ui-web/src');
  for (const name of fs.readdirSync(path.join(repo, 'modules/ui-web/public/themes'))) if (name.endsWith('.css')) files.push(`modules/ui-web/public/themes/${name}`);
  files.push('modules/ui-web/index.html');
  return scanSources(files.map(file => ({ file, source: fs.readFileSync(path.join(repo, file), 'utf8') })));
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const failures = scanTree();
  for (const f of failures) console.error(`${f.file}:${f.line}: ${f.property}: ${f.value} -> ${f.px}px (${f.reason}${f.definition ? `; definition ${f.definition}` : ''})`);
  const breakdown = {};
  for (const f of failures) { const key = `${f.property.startsWith('--') ? 'token' : f.property}: ${f.reason}`; breakdown[key] = (breakdown[key] || 0) + 1; }
  console.log(JSON.stringify({ violations: failures.length, breakdown }, null, 2));
  process.exitCode = failures.length ? 1 : 0;
}
