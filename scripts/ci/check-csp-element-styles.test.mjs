import assert from 'node:assert/strict';
import test from 'node:test';
import { scanSource } from './check-csp-element-styles.mjs';
const cases = [
  ['html`<div style="width: ${value}%"></div>`', 'bound-style-attribute'],
  ['html`<div style=${value}></div>`', 'bound-style-attribute'],
  ["html`<div style='color: ${value}; opacity: ${alpha}'></div>`", 'bound-style-attribute'],
  ['html`<div STYLE = ${value}></div>`', 'bound-style-attribute'],
  ['html`<div style="top: 0; ${styleMap(x)}"></div>`', 'bound-style-attribute'],
  ['html`<div style=${styleMap({ width: `${pct}%` })}></div>`', 'bound-style-attribute'],
  ['html`<div style="color:red;width:45%">${value}</div>`', 'literal-style-attribute'],
  ['html`<div style="color:red"></div>`', 'literal-style-attribute'],
  ['svg`<rect style=${paint}></rect>`', 'bound-style-attribute'],
  ['import { html as h } from "lit"; h`<div style="color:red"></div>`', 'literal-style-attribute'],
  ['import { html as h } from "lit"; h`<div style=${value}></div>`', 'bound-style-attribute'],
  ['import * as L from "lit"; L.html`<div style="color:red"></div>`', 'literal-style-attribute'],
  ['import { svg as s } from "lit"; s`<rect style=${paint}></rect>`', 'bound-style-attribute'],
  ['import { html as h } from "lit-html"; h`<div style="color:red"></div>`', 'literal-style-attribute'],
  ['import { html as staticHtml } from "lit/static-html.js"; staticHtml`<div style="color:red"></div>`', 'literal-style-attribute'],
  ['import { html as h } from "lit-html/static.js"; h`<div style="color:red"></div>`', 'literal-style-attribute'],
  ['import { html as h } from "lit-extra"; h`<div style="color:red"></div>`', 'literal-style-attribute'],
  ["el.setAttribute('style', value)", 'set-style-attribute'],
  ['root.innerHTML = `<style>:host{display:block}</style><div>${x}</div>`', 'markup-style-element'],
  ["root.innerHTML = '<style>p{}</style>'", 'markup-style-element'],
  ['el["setAttribute"]("style", value)', 'set-style-attribute'],
  ['el.setAttributeNS(null, "style", value)', 'set-style-attribute'],
  ["document.createElement('style')", 'runtime-style-element'],
  ['document.createElementNS("http://www.w3.org/1999/xhtml", "style")', 'runtime-style-element'],
];
for (const [source, kind] of cases) {
  test(source, () => assert.deepEqual(scanSource(source).map(h => h.kind), [kind]));
}
const allowed = [
  'html`<div data-style=${value} title="style=${value}"></div>`',
  'html`<div>${value}</div>`',
  'html`<div .style=${`width: ${pct}%`}></div>`',
  'html`<div class=${c} .style=${text}></div>`',
  'import { html as h } from "lit"; h`<div .style=${x}></div>`',
  'function h(strings) { return strings; } h`<div style="color:red"></div>`',
  'import { html as h } from "other-library"; h`<div style="color:red"></div>`',
  'import { html as h } from "lit"; other.h`<div style="color:red"></div>`',
  'html`<!-- <div style=${value}></div> -->`',
  '// document.createElement("style")\nconst message = "setAttribute(\\"style\\", x)";',
  'el.style.width = value; el.style.setProperty("--color", value)',
  'new CSSStyleSheet(); document.adoptedStyleSheets = sheets',
];
for (const source of allowed) test(source, () => assert.equal(scanSource(source).length, 0));
