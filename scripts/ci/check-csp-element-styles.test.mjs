import assert from 'node:assert/strict';
import { scanSource } from './check-csp-element-styles.mjs';
const cases = [
  ['html`<div style="width: ${value}%"></div>`', 'bound-style-attribute'],
  ['html`<div style=${value}></div>`', 'bound-style-attribute'],
  ["html`<div style='color: ${value}; opacity: ${alpha}'></div>`", 'bound-style-attribute'],
  ['html`<div STYLE = ${value}></div>`', 'bound-style-attribute'],
  ['html`<div style="top: 0; ${styleMap(x)}"></div>`', 'bound-style-attribute'],
  ['html`<div style=${styleMap({ width: `${pct}%` })}></div>`', 'bound-style-attribute'],
  ['html`<div style="color:red;width:45%">${value}</div>`', 'literal-style-attribute'],
  ["el.setAttribute('style', value)", 'set-style-attribute'],
  ['root.innerHTML = `<style>:host{display:block}</style><div>${x}</div>`', 'markup-style-element'],
  ["root.innerHTML = '<style>p{}</style>'", 'markup-style-element'],
  ['el["setAttribute"]("style", value)', 'set-style-attribute'],
  ['el.setAttributeNS(null, "style", value)', 'set-style-attribute'],
  ["document.createElement('style')", 'runtime-style-element'],
  ['document.createElementNS("http://www.w3.org/1999/xhtml", "style")', 'runtime-style-element'],
];
for (const [source, kind] of cases) assert.deepEqual(scanSource(source).map(h => h.kind), [kind], source);
for (const source of [
  'html`<div data-style=${value} title="style=${value}"></div>`',
  'html`<div>${value}</div>`',
  'html`<div .style=${`width: ${pct}%`}></div>`',
  'html`<div class=${c} .style=${text}></div>`',
  'html`<!-- <div style=${value}></div> -->`',
  '// document.createElement("style")\nconst message = "setAttribute(\\"style\\", x)";',
  'el.style.width = value; el.style.setProperty("--color", value)',
  'new CSSStyleSheet(); document.adoptedStyleSheets = sheets',
]) assert.equal(scanSource(source).length, 0, source);
console.log(`PASS: ${cases.length} forbidden fixtures and 8 allowed counterexamples`);
