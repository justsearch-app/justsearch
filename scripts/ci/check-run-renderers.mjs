#!/usr/bin/env node
/**
 * run-renderers gate — tempdoc 565 §12.6 (the composition tier's anti-drift).
 *
 * The agent run is composed by ONE ordered projection rendered through ONE tool-call primitive
 * (§12.3.A/B). Before §12 it rendered through TWO authorities — the live `ToolCallCard` and a static
 * record-half label — assembled by a dedup of two structures. §12 collapsed that fork; this gate keeps
 * it collapsed by locking the run-rendering authorities declared in `governance/run-renderers.v1.json`:
 *
 *  (1) **One tool-call primitive.** Only the registered mount sites render `<jf-tool-call-card>`.
 *      A new file mounting the tag = a second tool-render path.
 *  (2) **One tool-label authority.** Only `ToolCallCard.ts` imports `composeToolLabel` (the verb+target
 *      projection). A second importer = a second label compose path (the `tool · status` regression).
 *  (3) **One run projection.** Only the registered render sites import `projectUnifiedThread` /
 *      `projectLiveAgentActivity`. A second importer = a second run-assembly structure.
 *
 * Coverage = the full ui-web shell tree (a new file is scanned automatically); no enumerated allowlist
 * beyond the register's owners.
 *
 * HONEST SCOPE (tempdoc 565 §12.10 — register + DISCIPLINE, not a hard gate): this catches a fork that
 * IMPORTS the canonical types / MOUNTS the canonical tag. A re-model that hand-rolls its own tool-card
 * markup (a different tag) or its own run assembly WITHOUT importing the canonical symbols is
 * import-invisible and slips this grep. It is an early-warning
 * that forces review on a declared second renderer, not absolute prevention. Lighter scripts/ci tier;
 * wired as a ci.yml step + the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md).
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';
import { stripComments } from '../lib/strip-comments.mjs';

const REGISTER = 'governance/run-renderers.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const SRC = reg.scan.feRoot;
const EXCLUDE = reg.scan.excludeSuffixes;

const norm = (p) => p.replace(/\\/g, '/');
const toolMounts = new Set(reg.toolRenderer.mountSites.map(norm));
const labelOwner = norm(reg.toolRenderer.labelAuthority);
const labelPrimitive = norm(reg.toolRenderer.primitive);
const runSites = new Set(reg.runProjection.renderSites.map(norm));
// Tempdoc 565 §26.A — the run-segmentation authority (one assignRunSegments pass over the timeline).
const rsAuthority = new Set(reg.runSegments.consumerSites.map(norm));
const RS_SYMBOL = new RegExp(`\\b(${reg.runSegments.segmentSymbols.join('|')})\\b`);
// Tempdoc 565 §15.B — the answer-renderer authority (one answer block + one inline-citation weave).
const answerMounts = new Set(reg.answerRenderer.mountSites.map(norm));
const weaveSites = new Set(reg.answerRenderer.weaveSites.map(norm));

const TAG = new RegExp(`<${reg.toolRenderer.primitiveTag}\\b`);
const LABEL_IMPORT = /\bcomposeToolLabel\b/;
const PROJECTION_IMPORT = new RegExp(
  `\\b(${reg.runProjection.projectionSymbols.join('|')})\\b`,
);
const ANSWER_TAG = new RegExp(`<${reg.answerRenderer.primitiveTag}\\b`);
const WEAVE_IMPORT = new RegExp(`\\b(${reg.answerRenderer.weaveSymbols.join('|')})\\b`);

// Tempdoc 565 §15.A/§15.D.1/§6 — the grounding-semantics authority (one score→tier→class/label map).
const gs = reg.groundingSemantics;
const gsAuthority = norm(gs.authority);
const gsConsumers = new Set(gs.consumerSites.map(norm));
const GS_SYMBOL = new RegExp(`\\b(${gs.authoritySymbols.join('|')})\\b`);
// A grounding re-derivation = a numeric similarity threshold within ~60 chars of a grounding-class /
// label OUTPUT literal (either order). That output vocabulary co-occurs with a threshold ONLY in the
// authority; a consumer that merely calls evidenceTier/groundingClass holds neither — so this proximity
// scan fires precisely on a hand-rolled `score >= 0.X ? 'grounded'` and not on legit consumption.
const GS_THRESH = String.raw`[<>]=?\s*0?\.\d+`;
const GS_WORD = String.raw`['"\`](?:${gs.groundingOutputVocab.join('|')})['"\`]`;
const GS_REDERIVE = new RegExp(
  `(?:${GS_THRESH}[\\s\\S]{0,60}?${GS_WORD})|(?:${GS_WORD}[\\s\\S]{0,60}?${GS_THRESH})`,
);

// Tempdoc 565 §17 — the run-step presentation authority (one status → tone+glyph, one run-node primitive).
const sp = reg.stepPresentation;
const spPrimitive = norm(sp.primitive);
const spProjector = norm(sp.projector);
const spToneSites = new Set(sp.toneSites.map(norm));
const spNodeSites = new Set(sp.nodeSites.map(norm));
const SP_TAG = new RegExp(`<${sp.primitiveTag}\\b`);
const SP_TONE = new RegExp(`\\b(${sp.toneSymbols.join('|')})\\b`);
// A hand-rolled status glyph = a run-node glyph char as a ternary branch (`? '✓'` or `'✕' :`) — the
// import-INVISIBLE fork (no statusTone import, no <jf-run-node> mount). The primitive owns the chars in
// a Record map (not a ternary), so it never matches; excluded anyway.
const SP_GLYPHS = sp.glyphChars.join('');
const SP_FORK = new RegExp(
  `(?:\\?\\s*['"\\\`][${SP_GLYPHS}]['"\\\`])|(?:['"\\\`][${SP_GLYPHS}]['"\\\`]\\s*:)`,
);

// Tempdoc 577 §2.14 Root III (#18) — the tool-output text-provenance authority (one lineage projection).
const tl = reg.toolLineage;
const tlAuthority = norm(tl.authority);
const tlConsumers = new Set(tl.consumerSites.map(norm));
const TL_SYMBOL = new RegExp(`\\b(${tl.authoritySymbols.join('|')})\\b`);

// Tempdoc 577 Goal 3 §3.9a — the search-result evidence authority (one why/facet render).
const srr = reg.searchResultRendering;
const srrAuthorities = new Set(srr.authoritySites.map(norm));
const srrConsumers = new Set(srr.consumerSites.map(norm));
const SRR_SYMBOL = new RegExp(`\\b(${srr.authoritySymbols.join('|')})\\b`);

const isExcluded = (p) => EXCLUDE.some((s) => p.endsWith(s));

const files = [];
(function walk(d) {
  for (const e of readdirSync(d)) {
    const p = norm(join(d, e));
    const s = statSync(p);
    if (s.isDirectory()) {
      if (e === 'node_modules' || e === 'generated') continue;
      walk(p);
    } else if (extname(p) === '.ts' && !isExcluded(p)) {
      files.push(p);
    }
  }
})(SRC);

const failures = [];

for (const f of files) {
  const src = stripComments(readFileSync(f, 'utf8'));

  // (1) one tool-call primitive — only registered mount sites render the tag
  if (TAG.test(src) && !toolMounts.has(f)) {
    failures.push(
      `${f}: mounts <${reg.toolRenderer.primitiveTag}> outside the registered tool-renderer ` +
        `authority — render tool calls through the ONE primitive (565 §12.3.B); register a new ` +
        `mount site in ${REGISTER} only with review.`,
    );
  }
  // (2) one tool-label authority — only ToolCallCard.ts + the §17 step-presentation projector (which
  //     REUSES the one label authority for a tool step's label) import composeToolLabel.
  if (LABEL_IMPORT.test(src) && f !== labelOwner && f !== labelPrimitive && f !== spProjector) {
    failures.push(
      `${f}: imports composeToolLabel outside the tool-row primitive — the verb+target compose is ` +
        `the primitive's job (565 §12.3.B); a second compose path risks the "tool · status" regression.`,
    );
  }
  // (3) one run projection — only registered render sites import the projection
  if (PROJECTION_IMPORT.test(src) && !runSites.has(f)) {
    failures.push(
      `${f}: imports the run projection (${reg.runProjection.projectionSymbols.join('/')}) outside ` +
        `the registered render site — the run is ONE ordered projection (565 §12.3.A); a second ` +
        `consumer is a second run-assembly structure.`,
    );
  }
  // (3b) one run-segmentation authority — only the projection module computes assignRunSegments (565 §26.A)
  if (RS_SYMBOL.test(src) && !rsAuthority.has(f)) {
    failures.push(
      `${f}: uses the run-segmentation authority (${reg.runSegments.segmentSymbols.join('/')}) outside ` +
        `the registered authority module — the run's STRUCTURE is computed ONCE (565 §26.A); a second ` +
        `segmentation pass is the leaf-fork the typed RunSegmentRef seam guards against.`,
    );
  }
  // (4) one answer-text renderer — only registered mount sites render <jf-markdown-block>
  if (ANSWER_TAG.test(src) && !answerMounts.has(f)) {
    failures.push(
      `${f}: mounts <${reg.answerRenderer.primitiveTag}> outside the registered answer-renderer ` +
        `authority — every mode renders the answer through the ONE block (565 §15.B); a second ` +
        `answer-text block is the leaf-fork StreamingTextBlock retirement collapsed.`,
    );
  }
  // (5) one inline-citation weave — only registered weave sites import the claim→Citation resolver
  if (WEAVE_IMPORT.test(src) && !weaveSites.has(f)) {
    failures.push(
      `${f}: imports the citation weave (${reg.answerRenderer.weaveSymbols.join('/')}) outside the ` +
        `registered weave site — the claim→Citation mapping is ONE authority (565 §15.B); a second ` +
        `owner is a second inline-citation grammar.`,
    );
  }
  // (6) one grounding-semantics authority — only registered consumers import the tier-classification symbols
  if (f !== gsAuthority && GS_SYMBOL.test(src) && !gsConsumers.has(f)) {
    failures.push(
      `${f}: imports a grounding-tier symbol (${gs.authoritySymbols.join('/')}) outside the registered ` +
        `consumer sites — the score→tier→grounding mapping is ONE authority (565 §15.A); register a new ` +
        `consumer in ${REGISTER} only with review, or it is a second grounding classifier.`,
    );
  }
  // (7) no grounding re-derivation — only the authority maps a numeric threshold to a grounding class/label
  if (f !== gsAuthority && GS_REDERIVE.test(src)) {
    failures.push(
      `${f}: re-derives a grounding class from a numeric threshold (a \`score >= 0.X ? 'grounded'\` ` +
        `shape) outside the authority — the tier thresholds live ONCE in evidenceProjection ` +
        `(565 §15.A/§15.D.1 seam); call evidenceTier/groundingClass/groundingLabel, never hardcode the cutoff.`,
    );
  }
  // (8) one status authority — only registered toneSites import the status → tone/glyph symbols
  if (SP_TONE.test(src) && !spToneSites.has(f)) {
    failures.push(
      `${f}: imports a status-presentation symbol (${sp.toneSymbols.join('/')}) outside the registered ` +
        `toneSites — a run step's tone+glyph project ONCE through statusTone + runStepPresentation ` +
        `(565 §17); register a consumer in ${REGISTER} only with review, or it is a second status classifier.`,
    );
  }
  // (9) one run-node primitive — only registered node sites mount <jf-run-node>
  if (SP_TAG.test(src) && !spNodeSites.has(f)) {
    failures.push(
      `${f}: mounts <${sp.primitiveTag}> outside the registered run-node sites — every run step renders ` +
        `its glyph through the ONE primitive (565 §17); register a mount site in ${REGISTER} only with review.`,
    );
  }
  // (10) no hand-rolled status glyph — only the run-node primitive maps a status to a glyph char
  if (f !== spPrimitive && SP_FORK.test(src)) {
    failures.push(
      `${f}: hand-rolls a status glyph (a \`? '✓'\` / \`'✕' :\` ternary) outside the run-node primitive — ` +
        `the status→glyph map lives ONCE in statusTone/RunNode (565 §17); compose stepPresentation + ` +
        `<jf-run-node> instead.`,
    );
  }
  // (11) one tool-output lineage authority — only registered consumers import the framing symbols
  if (f !== tlAuthority && TL_SYMBOL.test(src) && !tlConsumers.has(f)) {
    failures.push(
      `${f}: imports a tool-output lineage symbol (${tl.authoritySymbols.join('/')}) outside the ` +
        `registered consumer sites — the text-provenance framing is ONE authority (577 §2.14 Root III ` +
        `#18); register a new consumer in ${REGISTER} only with review, or it is a second lineage classifier.`,
    );
  }
  // (12) one search-result evidence render — only registered consumers import the why/facet render symbols
  if (!srrAuthorities.has(f) && SRR_SYMBOL.test(src) && !srrConsumers.has(f)) {
    failures.push(
      `${f}: imports a search-result render symbol (${srr.authoritySymbols.join('/')}) outside the ` +
        `registered consumer sites — the per-hit "why" + facet-chip render is ONE authority consumed by ` +
        `the retrieve tier / ResultsCard (577 §3.9a, retired standalone SearchSurface folded in at ` +
        `Search Thread S5b); register a new consumer in ${REGISTER} only with ` +
        `review, or it is a second fork of the search-result evidence render.`,
    );
  }
}

if (failures.length > 0) {
  console.error('✗ run-renderers gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'));
  process.exit(1);
}
console.log(
  `✓ run-renderers gate OK — one tool-call primitive (${reg.toolRenderer.primitiveTag}, ` +
    `${toolMounts.size} mount sites), one label authority, one run projection ` +
    `(${reg.runProjection.projectionSymbols.join(' + ')}), one answer renderer ` +
    `(${reg.answerRenderer.primitiveTag}, ${answerMounts.size} mount sites) + one citation weave ` +
    `(${reg.answerRenderer.weaveSymbols.join('/')}), one grounding-semantics authority ` +
    `(evidenceProjection, ${gsConsumers.size} consumer sites; no out-of-authority threshold re-derivation), ` +
    `one run-step presentation authority (${sp.primitiveTag}, ${spNodeSites.size} node sites, ` +
    `${spToneSites.size} tone sites; no hand-rolled status glyph), one tool-output lineage authority ` +
    `(toolOutputLineage, ${tlConsumers.size} consumer site), one search-result evidence render ` +
    `(whyThisResult + facetChips, ${srrConsumers.size} consumer sites — 577 §3.9a). ` +
    `Early-warning only — import-invisible re-models still need the §12.9 measured UX-audit discipline.`,
);
