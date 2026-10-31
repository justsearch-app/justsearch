// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 450 §1.7 — minimal Lit-friendly SVG icon helper.
 *
 * Inline SVG paths (sourced from lucide v0.577 — same icon set the React
 * side uses via lucide-react) so Lit templates have a CSS-stylable
 * <svg>-returning helper without pulling lucide-react into the
 * non-React boot graph.
 * Attribution: packaging/legal/LUCIDE-LICENSE.txt, shipped as legal/LUCIDE-LICENSE.txt.
 *
 * Adding an icon: copy the SVG body (path/circle/etc. children) from
 * https://lucide.dev/icons/<name> as the value. Keep the surrounding
 * <svg> contract: 24x24 viewBox, currentColor stroke, 2px width.
 *
 * Why inline rather than a new lucide vanilla dependency: avoids a
 * lockfile churn on a non-load-bearing concern; the icon-set used
 * across the framework is small enough (≲20) that inline scales.
 * If the count grows past ~30, switch to the vanilla lucide package.
 */

import { svg, type SVGTemplateResult } from 'lit';

type IconName =
  | 'folder'
  | 'folder-plus'
  | 'folder-tree'
  | 'refresh-cw'
  | 'more-horizontal'
  | 'trash-2'
  | 'alert-circle'
  | 'check-circle-2'
  | 'clock'
  | 'loader-2'
  | 'hard-drive'
  | 'x'
  | 'chevron-down'
  | 'bot'
  | 'send'
  | 'shield'
  | 'wifi'
  | 'keyboard'
  | 'palette'
  | 'layers'
  | 'monitor'
  | 'moon'
  | 'sun'
  | 'file-text'
  | 'file-down'
  | 'database'
  | 'memory-stick'
  | 'zap'
  | 'cpu'
  | 'server'
  | 'power'
  | 'help-circle'
  | 'maximize-2'
  | 'list'
  | 'play'
  | 'square'
  | 'search'
  | 'x-circle'
  | 'arrow-right-left'
  | 'alert-triangle'
  | 'download'
  // Slice 477 H1 — Settings UI V1.5 sections
  | 'circle'
  | 'menu'
  | 'chevron-up'
  | 'package'
  // Slice 486 G36 — pin/bookmark glyph for pinned-search button
  | 'bookmark'
  // Tempdoc 870 item 5 — the product's ONE copy glyph (lucide `copy`). Replaced the five-path
  // `clipboard-copy`, whose clipboard body + inbound arrow read as a busy smudge at 11-15px.
  | 'copy'
  // Slice 486 F15-narrow — history glyph for the Activity surface rail icon
  | 'history'
  // Slice 495 F6 — fork glyph for conversation forking
  | 'git-branch'
  // Slice 497 — unified chat surface rail icon
  | 'message-square'
  // Slice 501 — navigation chrome
  | 'chevron-left'
  | 'chevron-right'
  // Tempdoc 577 Phase 7 — typed result-row glyphs
  | 'code'
  | 'image'
  // Tempdoc 567 §8 (deferred → built) — custom-theme management: rename + import-from-JSON
  | 'pencil'
  | 'upload'
  // Tempdoc 586 P-5 — gear glyph for the Settings rail entry (replaces the misleading 'power').
  | 'settings';

const PATHS: Record<IconName, SVGTemplateResult> = {
  folder: svg`<path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z" />`,
  'folder-plus': svg`<path d="M12 10v6" /><path d="M9 13h6" /><path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z" />`,
  'refresh-cw': svg`<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" /><path d="M21 3v5h-5" /><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" /><path d="M8 16H3v5" />`,
  // Lucide "more-horizontal" — tempdoc 610 §D.2 per-turn action-bar overflow (⋯) trigger.
  'more-horizontal': svg`<circle cx="12" cy="12" r="1" /><circle cx="19" cy="12" r="1" /><circle cx="5" cy="12" r="1" />`,
  'trash-2': svg`<path d="M3 6h18" /><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" /><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /><line x1="10" x2="10" y1="11" y2="17" /><line x1="14" x2="14" y1="11" y2="17" />`,
  'alert-circle': svg`<circle cx="12" cy="12" r="10" /><line x1="12" x2="12" y1="8" y2="12" /><line x1="12" x2="12.01" y1="16" y2="16" />`,
  'check-circle-2': svg`<circle cx="12" cy="12" r="10" /><path d="m9 12 2 2 4-4" />`,
  clock: svg`<circle cx="12" cy="12" r="10" /><polyline points="12 6 12 12 16 14" />`,
  'loader-2': svg`<path d="M21 12a9 9 0 1 1-6.219-8.56" />`,
  'hard-drive': svg`<line x1="22" x2="2" y1="12" y2="12" /><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" /><line x1="6" x2="6.01" y1="16" y2="16" /><line x1="10" x2="10.01" y1="16" y2="16" />`,
  x: svg`<path d="M18 6 6 18" /><path d="m6 6 12 12" />`,
  'folder-tree': svg`<path d="M20 10a1 1 0 0 0 1-1V6a1 1 0 0 0-1-1h-2.5a1 1 0 0 1-.8-.4l-.9-1.2A1 1 0 0 0 15 3h-2a1 1 0 0 0-1 1v5a1 1 0 0 0 1 1Z" /><path d="M20 21a1 1 0 0 0 1-1v-3a1 1 0 0 0-1-1h-2.9a1 1 0 0 1-.88-.55l-.42-.85a1 1 0 0 0-.92-.6H13a1 1 0 0 0-1 1v5a1 1 0 0 0 1 1Z" /><path d="M3 5a2 2 0 0 0 2 2h3" /><path d="M3 3v13a2 2 0 0 0 2 2h3" />`,
  'chevron-down': svg`<path d="m6 9 6 6 6-6" />`,
  bot: svg`<path d="M12 8V4H8" /><rect width="16" height="12" x="4" y="8" rx="2" /><path d="M2 14h2" /><path d="M20 14h2" /><path d="M15 13v2" /><path d="M9 13v2" />`,
  send: svg`<path d="M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z" /><path d="m21.854 2.147-10.94 10.939" />`,
  shield: svg`<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" />`,
  wifi: svg`<path d="M5 13a10 10 0 0 1 14 0" /><path d="M8.5 16.5a5 5 0 0 1 7 0" /><path d="M2 8.82a15 15 0 0 1 20 0" /><line x1="12" x2="12.01" y1="20" y2="20" />`,
  keyboard: svg`<path d="M10 8h.01" /><path d="M12 12h.01" /><path d="M14 8h.01" /><path d="M16 12h.01" /><path d="M18 8h.01" /><path d="M6 8h.01" /><path d="M7 16h10" /><path d="M8 12h.01" /><rect width="20" height="16" x="2" y="4" rx="2" />`,
  palette: svg`<circle cx="13.5" cy="6.5" r=".5" fill="currentColor" /><circle cx="17.5" cy="10.5" r=".5" fill="currentColor" /><circle cx="8.5" cy="7.5" r=".5" fill="currentColor" /><circle cx="6.5" cy="12.5" r=".5" fill="currentColor" /><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z" />`,
  layers: svg`<path d="M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z" /><path d="m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65" /><path d="m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65" />`,
  monitor: svg`<rect width="20" height="14" x="2" y="3" rx="2" /><line x1="8" x2="16" y1="21" y2="21" /><line x1="12" x2="12" y1="17" y2="21" />`,
  moon: svg`<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" />`,
  sun: svg`<circle cx="12" cy="12" r="4" /><path d="M12 2v2" /><path d="M12 20v2" /><path d="m4.93 4.93 1.41 1.41" /><path d="m17.66 17.66 1.41 1.41" /><path d="M2 12h2" /><path d="M20 12h2" /><path d="m6.34 17.66-1.41 1.41" /><path d="m19.07 4.93-1.41 1.41" />`,
  'file-text': svg`<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z" /><path d="M14 2v4a2 2 0 0 0 2 2h4" /><path d="M10 9H8" /><path d="M16 13H8" /><path d="M16 17H8" />`,
  'file-down': svg`<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z" /><path d="M14 2v4a2 2 0 0 0 2 2h4" /><path d="M12 18v-6" /><path d="m9 15 3 3 3-3" />`,
  database: svg`<ellipse cx="12" cy="5" rx="9" ry="3" /><path d="M3 5V19A9 3 0 0 0 21 19V5" /><path d="M3 12A9 3 0 0 0 21 12" />`,
  'memory-stick': svg`<path d="M6 19v-3" /><path d="M10 19v-3" /><path d="M14 19v-3" /><path d="M18 19v-3" /><path d="M8 11V9" /><path d="M16 11V9" /><path d="M12 11V9" /><path d="M2 15h20" /><path d="M2 7a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v1.1a2 2 0 0 0 0 3.837V17a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-5.1a2 2 0 0 0 0-3.837Z" />`,
  zap: svg`<path d="M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z" />`,
  cpu: svg`<rect x="4" y="4" width="16" height="16" rx="2" /><rect x="9" y="9" width="6" height="6" /><path d="M15 2v2" /><path d="M15 20v2" /><path d="M2 15h2" /><path d="M2 9h2" /><path d="M20 15h2" /><path d="M20 9h2" /><path d="M9 2v2" /><path d="M9 20v2" />`,
  server: svg`<rect width="20" height="8" x="2" y="2" rx="2" ry="2" /><rect width="20" height="8" x="2" y="14" rx="2" ry="2" /><line x1="6" x2="6.01" y1="6" y2="6" /><line x1="6" x2="6.01" y1="18" y2="18" />`,
  power: svg`<path d="M12 2v10" /><path d="M18.4 6.6a9 9 0 1 1-12.77.04" />`,
  'help-circle': svg`<circle cx="12" cy="12" r="10" /><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" /><path d="M12 17h.01" />`,
  'maximize-2': svg`<polyline points="15 3 21 3 21 9" /><polyline points="9 21 3 21 3 15" /><line x1="21" x2="14" y1="3" y2="10" /><line x1="3" x2="10" y1="21" y2="14" />`,
  list: svg`<line x1="8" x2="21" y1="6" y2="6" /><line x1="8" x2="21" y1="12" y2="12" /><line x1="8" x2="21" y1="18" y2="18" /><line x1="3" x2="3.01" y1="6" y2="6" /><line x1="3" x2="3.01" y1="12" y2="12" /><line x1="3" x2="3.01" y1="18" y2="18" />`,
  play: svg`<polygon points="6 3 20 12 6 21 6 3" />`,
  square: svg`<rect width="18" height="18" x="3" y="3" rx="2" />`,
  search: svg`<circle cx="11" cy="11" r="8" /><path d="m21 21-4.3-4.3" />`,
  'x-circle': svg`<circle cx="12" cy="12" r="10" /><path d="m15 9-6 6" /><path d="m9 9 6 6" />`,
  'arrow-right-left': svg`<path d="m16 3 4 4-4 4" /><path d="M20 7H4" /><path d="m8 21-4-4 4-4" /><path d="M4 17h16" />`,
  'alert-triangle': svg`<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3" /><path d="M12 9v4" /><path d="M12 17h.01" />`,
  download: svg`<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" x2="12" y1="15" y2="3" />`,
  // Slice 477 H1 — Lucide-aligned glyphs added for Settings UI V1.5 sections
  circle: svg`<circle cx="12" cy="12" r="10" />`,
  menu: svg`<line x1="4" x2="20" y1="12" y2="12" /><line x1="4" x2="20" y1="6" y2="6" /><line x1="4" x2="20" y1="18" y2="18" />`,
  'chevron-up': svg`<path d="m18 15-6-6-6 6" />`,
  package: svg`<path d="M16.5 9.4 7.55 4.24" /><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" /><polyline points="3.29 7 12 12 20.71 7" /><line x1="12" x2="12" y1="22" y2="12" />`,
  // Lucide "bookmark" — slice 486 G36 pin glyph.
  bookmark: svg`<path d="m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16z" />`,
  // Lucide "copy" — tempdoc 870 item 5. Two rounded rects, and nothing else.
  copy: svg`<rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>`,
  // Lucide "history" — slice 486 F15-narrow Activity surface rail icon.
  history: svg`<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" /><path d="M3 3v5h5" /><path d="M12 7v5l4 2" />`,
  // Lucide "git-branch" — slice 495 F6 conversation fork button.
  'git-branch': svg`<line x1="6" x2="6" y1="3" y2="15" /><circle cx="18" cy="6" r="3" /><circle cx="6" cy="18" r="3" /><path d="M18 9a9 9 0 0 1-9 9" />`,
  'message-square': svg`<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />`,
  'chevron-left': svg`<path d="m15 18-6-6 6-6" />`,
  'chevron-right': svg`<path d="m9 18 6-6-6-6" />`,
  // Tempdoc 577 Phase 7 — typed result-row glyphs (lucide `code-2` / `image`).
  'code': svg`<polyline points="16 18 22 12 16 6" /><polyline points="8 6 2 12 8 18" />`,
  'image': svg`<rect width="18" height="18" x="3" y="3" rx="2" ry="2" /><circle cx="9" cy="9" r="2" /><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21" />`,
  // Lucide "pencil" — tempdoc 567 custom-theme rename glyph.
  pencil: svg`<path d="M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z" /><path d="m15 5 4 4" />`,
  // Lucide "upload" — tempdoc 567 import-from-JSON glyph.
  upload: svg`<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="17 8 12 3 7 8" /><line x1="12" x2="12" y1="3" y2="15" />`,
  // Lucide "settings" (gear) — tempdoc 586 P-5 Settings rail glyph (replaces 'power').
  settings: svg`<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" /><circle cx="12" cy="12" r="3" />`,
};

export interface IconProps {
  name: IconName;
  /** Pixel size; default 16. */
  size?: number;
  /** Extra `class` to forward (parent template controls coloring via currentColor). */
  className?: string;
  /** Optional spin animation hint — caller adds the keyframe via CSS. */
  spin?: boolean;
}

/**
 * Render a Lucide icon as an inline <svg>. Designed for
 * call-site composition inside `html\`...\`` templates:
 *
 *   import { icon } from '../components/Icon.js';
 *   render() { return html`${icon({ name: 'folder', size: 18 })} Library`; }
 *
 * Coloring: forward via parent's `color` (the SVG uses
 * `stroke="currentColor"`). Sizing: use `size` (default 16) or
 * pass `width`/`height` via inline style on the parent.
 */
export function icon(props: IconProps): SVGTemplateResult {
  const size = props.size ?? 16;
  const cls = props.className ? props.className : '';
  return svg`
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="${size}"
      height="${size}"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      class="${cls}${props.spin ? ' jf-icon-spin' : ''}"
      part="jf-icon"
    >${PATHS[props.name]}</svg>
  `;
}

export type { IconName };
