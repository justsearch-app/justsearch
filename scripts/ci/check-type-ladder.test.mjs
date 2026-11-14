import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { scanSources, scanTree, sizes } from './check-type-ladder.mjs';
const scan = (source, file = 'fixture.css') => scanSources([{ file, source }]);
const forbidden = [
  ['uppercase property', 'p { FONT-SIZE: 14PX; }'],
  ['literal size', 'p { font-size: 14px; }'],
  ['relative heading size', 'h2 { font-size: 1.5em; }'],
  ['literal radius', 'p { border-radius: 6px; }'],
  ['token definition', ':root { --font-size-body: 14px; }'],
  ['scoped override', ':root { --s: 13px; } :host { --s: 14px; } p { font-size: var(--s); }'],
  ['theme override', ':root { --r: 4px; } [data-theme=light] { --r: 6px; } p { border-radius: var(--r); }'],
  ['fallback even when defined', ':root { --s: 13px; } p { font-size: var(--s, 14px); }'],
  ['nested fallback', 'p { font-size: var(--s, var(--missing, 14px)); }'],
  ['forbidden in token chain', ':root { --a: var(--b); --b: 14px; } p { font-size: var(--a); }'],
  ['font shorthand', 'p { font: italic 500 14px/1.5 sans-serif; }'],
  ['font shorthand via token', ':root { --f: italic 14px sans-serif; } p { font: var(--f); }'],
  ...['larger', 'smaller', 'small', 'medium', 'initial', 'unset', '1em', '100%', '0', 'calc(13px)', 'min(13px, 15px)', 'clamp(11px, 13px, 15px)'].map(v => [`font ${v}`, `p { font-size: ${v}; }`]),
  ['unresolvable', 'p { font-size: var(--missing); }'],
  ['cycle', ':root { --a: var(--b); --b: var(--a); } p { font-size: var(--a); }'],
  ['cycle despite fallback', ':root { --a: var(--a, 13px); } p { font-size: var(--a, 15px); }'],
  ['empty fallback', 'p { font-size: var(--x,); }'],
  ['radius tuple', 'p { border-radius: 4px 6px 8px 14px; }'],
  ['radius slash', 'p { border-radius: 4px 8px / 8px 6px; }'],
  ['radius longhand', 'p { border-top-left-radius: 4px 6px; }'],
  ['logical longhand', 'p { border-start-end-radius: 6px; }'],
  ['radius relative', 'p { border-radius: 1em; }'],
  ['radius percentage', 'p { border-radius: 25%; }'],
  ['radius math', 'p { border-radius: calc(4px); }'],
  ['cyclic radius', ':root { --r: var(--s); --s: var(--r); } p { border-radius: var(--r); }'],
  ['unresolved radius', 'p { border-radius: var(--missing); }'],
  ['invalid tuple', 'p { border-radius: 0 4px 8px 14px 0; }'],
];
for (const [name, source] of forbidden) test(`rejects ${name}`, () => {
  const hits = scan(source);
  assert.ok(hits.length, source);
  for (const hit of hits) { assert.equal(hit.file, 'fixture.css'); assert.ok(hit.line > 0); assert.ok(hit.property); assert.ok(hit.reason); assert.notEqual(hit.px, undefined); }
});
for (const v of [...sizes.map(n => `${n}px`), ...sizes.map(n => `${n / 16}rem`), 'inherit']) test(`allows font ${v}`, () => assert.deepEqual(scan(`p { font-size: ${v}; }`), []));
for (const v of ['0', '0px', '4px', '8px', '14px', '9999px', '50%', 'inherit', '4px 8px 14px 0 / 0 14px 8px 4px']) test(`allows radius ${v}`, () => assert.deepEqual(scan(`p { border-radius: ${v}; }`), []));
test('allowed chain, nested fallbacks and font shorthand', () => assert.deepEqual(scan(`
:root { --a: var(--b); --b: 0.8125rem; --r: 4px; }
p { font-size: var(--a, var(--absent, 15px)); border-top-left-radius: var(--r) 50%; font: italic 500 13px/1.5 'JustSearch Inter'; }
`), []));
test('root remains 16px; a text element at 16px fails', () => {
  assert.deepEqual(scan('html { font-size: 16px; } :root { font-size: 1rem; }'), []);
  assert.equal(scan('p { font-size: 16px; }').length, 1);
});
test('invalid replacement with the same distinct-size count fails', () => {
  assert.deepEqual(scan(sizes.map(n => `.s${n}{font-size:${n}px}`).join('\n')), []);
  assert.equal(scan(sizes.map(n => `.s${n}{font-size:${n === 13 ? 14 : n}px}`).join('\n')).length, 1);
});
const tsCases = [
  ['css template', 'const styles = css`p { font-size: 14px; }`;'],
  ['template expression', 'const styles = css`p { font-size: ${size}px; }`;'],
  ['cssText', "el.style.cssText = 'font-size: 14px; border-radius: 6px';"],
  ['compound fontSize assignment', "el.style.fontSize += '14px';"],
  ['fontSize', "el.style.fontSize = '14px';"],
  ['borderRadius', "el.style.borderRadius = '6px';"],
  ['setProperty font', "el.style.setProperty('font-size', '14px');"],
  ['setProperty radius', "el.style.setProperty('border-radius', '6px');"],
  ['setProperty token', "el.style.setProperty('--font-size-test', '14px');"],
  ['setProperty used token', "el.style.setProperty('--s', '14px'); const s = css`p{font-size:var(--s)}`;"],
  ['assembled declaration', "const s = 'font-' + 'size: ' + '14px';"],
  ['assembled via variable', "const p = 'font-size'; const s = p + ': 14px';"],
  ['unknown expression', 'el.style.fontSize = size;'],
  ['unknown cssText', 'el.style.cssText = styles;'],
  ['mutable binding', "let size = '13px'; size = '14px'; el.style.fontSize = size;"],
  ['ambiguous binding', "const size = '14px'; function f(){const size = '13px'} el.style.fontSize = size;"],
  ['inline markup', 'html`<div style="border-radius: 6px">Text</div>`'],
];
for (const [name, source] of tsCases) test(`rejects TS ${name}`, () => assert.ok(scan(source, 'fixture.ts').length, source));
const receiverCases = [
  ['aliased fontSize', v => `const s = el.style; s.fontSize = '${v}px';`, 17, 13],
  ['destructured style', v => `const { style } = el; style.borderRadius = '${v}px';`, 6, 4],
  ['style parameter', v => `function f(st: CSSStyleDeclaration) { st.fontSize = '${v}px'; }`, 17, 13],
  ['aliased setProperty', v => `const s = el.style; s.setProperty('font-size', '${v}px');`, 17, 13],
  ['aliased cssText', v => `const s = el.style; s.cssText = 'font-size: ${v}px';`, 17, 13],
  ['Object.assign fontSize', v => `Object.assign(el.style, { fontSize: '${v}px' });`, 17, 13],
  ['Object.assign radius', v => `Object.assign(st, { borderRadius: '${v}px' });`, 6, 4],
  ['aliased font', v => `st.font = 'italic ${v}px sans-serif';`, 17, 13],
  ['aliased bracket setter', v => `const p = 'fontSize'; st[p] = '${v}px';`, 17, 13],
  ['aliased setProperty font', v => `st.setProperty('font', '${v}px sans-serif');`, 17, 13],
  ['aliased setProperty radius', v => `st.setProperty('border-radius', '${v}px');`, 6, 4],
  ['aliased setProperty token', v => `st.setProperty('--font-size-test', '${v}px');`, 17, 13],
  ['Object.assign shorthand', v => `const fontSize = '${v}px'; Object.assign(st, { fontSize });`, 17, 13],
  ['Object.assign computed key', v => `const p = 'fontSize'; Object.assign(st, { [p]: '${v}px' });`, 17, 13],
  ['Object.assign cssText', v => `Object.assign(st, { cssText: 'font-size: ${v}px' });`, 17, 13],
];
for (const corner of ['TopLeft', 'TopRight', 'BottomLeft', 'BottomRight', 'StartStart', 'StartEnd', 'EndStart', 'EndEnd']) {
  const property = `border${corner}Radius`;
  const cssProperty = property.replace(/[A-Z]/g, c => `-${c.toLowerCase()}`);
  receiverCases.push(
    [`aliased ${property}`, v => `st.${property} = '${v}px';`, 6, 4],
    [`aliased setProperty ${cssProperty}`, v => `st.setProperty('${cssProperty}', '${v}px');`, 6, 4],
    [`Object.assign ${property}`, v => `Object.assign(st, { ${property}: '${v}px' });`, 6, 4],
  );
}
for (const [name, source, forbidden, allowed] of receiverCases) {
  test(`rejects TS ${name}`, () => assert.ok(scan(source(forbidden), 'fixture.ts').length, source(forbidden)));
  test(`allows TS ${name}`, () => assert.deepEqual(scan(source(allowed), 'fixture.ts'), []));
}
for (const source of [
  'st.fontSize = size;',
  'st.borderRadius = radius;',
  'st.font = font;',
  'st.cssText = styles;',
  "st.setProperty('font-size', size);",
  'st.setProperty(property, value);',
  'Object.assign(st, { fontSize: size });',
  'Object.assign(st, { borderRadius: radius });',
  'Object.assign(st, { cssText: styles });',
  "st.fontSize += '13px';",
  "st.cssText += 'font-size:13px';",
]) test(`rejects unresolved TS ${source}`, () => assert.ok(scan(source, 'fixture.ts').length, source));
test('allows property reads and plain object definitions', () => assert.deepEqual(scan(`
const x = s.fontSize;
const { borderRadius } = s;
st.setProperty('width', '17px');
Object.assign(st, { width: '17px' });
const settings = { fontSize: '17px', borderRadius: '6px' };
`, 'fixture.ts'), []));
test('allows canvas 2D font assignments, including property receivers and aliases', () => assert.deepEqual(scan(`
const context = document.createElement('canvas').getContext('2d', { willReadFrequently: true });
context.font = \`600 \${overlaySize * 0.625}px sans-serif\`;
const other = context.canvas.getContext('2d');
const alias = other;
alias['font'] = '17px sans-serif';
`, 'fixture.ts'), []));
test('still checks font assignments on unrelated or unproven objects', () => {
  for (const source of [
    "const context = canvas.getContext('2d'); unrelated.font = '17px sans-serif';",
    "const context = canvas.getContext('webgl'); context.font = '17px sans-serif';",
    "let context = canvas.getContext('2d'); context = unrelated; context.font = '17px sans-serif';",
    "const context = canvas.getContext('2d'); function f(context) { context.font = '17px sans-serif'; }",
    "const context = canvas.getContext('2d'); function f({ context }) { context.font = '17px sans-serif'; }",
    "const context = canvas.getContext('2d'); function f() { const { context } = unrelated; context.font = '17px sans-serif'; }",
    "const context = canvas.getContext('2d'); context.canvas.style.font = '17px sans-serif';",
    "import context from './other'; function f() { const context = canvas.getContext('2d'); } context.font = '17px sans-serif';",
  ]) assert.ok(scan(source, 'fixture.ts').length, source);
});
test('does not scan comments or ordinary numeric properties', () => assert.deepEqual(scan(`
// font-size: 14px;
/* css\`p { font-size: 14px; }\` */
const s = css\`p{width:14px;line-height:1.6;font-size:13px;border-radius:4px}\`;
el.style.width = '14px';
`, 'fixture.ts'), []));
test('reports definition and use lines across files', () => {
  const hits = scanSources([{ file: 'tokens.css', source: ':root {\n --s: 14px;\n}' }, { file: 'use.ts', source: '\nconst s = css`p{font-size:var(--s)}`;' }]);
  assert.equal(hits[0].line, 2); assert.equal(hits[0].definition, 'tokens.css:2'); assert.equal(hits[0].px, '14');
});
test('tree includes boot, themes, index, CSS and non-test TS, excluding tests', () => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'type-ladder-fixture-'));
  try {
    for (const [file, source] of Object.entries({
      'modules/ui-web/src/boot/page.ts': 'const s = css`p{font-size:14px}`;',
      'modules/ui-web/src/a.css': 'p{font-size:14px}',
      'modules/ui-web/src/a.test.ts': 'const s = css`p{font-size:14px}`;',
      'modules/ui-web/public/themes/light.css': ':root{--font-size-body:14px}',
      'modules/ui-web/index.html': '<style>p{font-size:14px}</style>',
    })) { mkdirSync(path.dirname(path.join(dir, file)), { recursive: true }); writeFileSync(path.join(dir, file), source); }
    assert.equal(scanTree(dir).length, 4);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('resolves exported literal CSSOM names while unknown names fail', () => {
  const module = { file: 'state/names.ts', source: "export const WIDTH = '--measure-prose'; export const SIZE = 'font-size';" };
  assert.deepEqual(scanSources([module, { file: 'views/use.ts', source: "import { WIDTH } from '../state/names.js'; el.style.setProperty(WIDTH, width());" }]), []);
  assert.ok(scanSources([module, { file: 'views/use.ts', source: "import { SIZE as property } from '../state/names.js'; el.style.setProperty(property, '14px');" }]).length);
  assert.ok(scan("el.style.setProperty(unknownName, '14px');", 'use.ts').length);
});
