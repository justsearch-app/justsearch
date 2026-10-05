import fs from 'fs';
import fg from 'fast-glob';
import matter from 'gray-matter';
import { execFileSync } from 'node:child_process';

const files = await fg(['docs/**/*.md', '!docs/_**/*']);

/**
 * Markdown body with fenced code blocks removed, so heading rules see prose only.
 * Opening and closing fences are ``` or ~~~ (3+), optionally indented up to 3 spaces; a fence
 * closes only on the same character at the same or greater length, so a ``` block containing
 * ~~~ stays open.
 */
function stripFencedCode(md) {
  const out = [];
  let fence = null;
  for (const line of md.split(/\r?\n/)) {
    const m = /^ {0,3}(`{3,}|~{3,})/.exec(line);
    if (fence) {
      if (m && m[1][0] === fence[0] && m[1].length >= fence.length) fence = null;
      continue;
    }
    if (m) {
      fence = m[1];
      continue;
    }
    out.push(line);
  }
  return out.join('\n');
}

let issues = [];

for (const f of files) {
  const raw = fs.readFileSync(f, 'utf8');
  if (raw.includes('\uFFFD')) {
    issues.push({ file: f, kind: 'encoding', msg: 'Found U+FFFD replacement character' });
  }
  // A single malformed front matter must be a FINDING, not a crash that hides every other
  // result (872: two tempdocs each killed the whole run for weeks, unreported).
  let fm;
  try {
    fm = matter(raw);
  } catch (e) {
    issues.push({ file: f, kind: 'frontmatter-parse', severity: 'error', msg: `Front matter does not parse: ${e.reason || e.message}` });
    continue;
  }
  const data = fm.data || {};
  // Detect duplicate front-matter keys (best-effort, top-level only)
  if (fm.matter && fm.matter.startsWith('---')) {
    const linesFM = fm.matter.split(/\r?\n/).slice(1); // skip leading ---
    const keyCounts = new Map();
    for (const line of linesFM) {
      if (/^---\s*$/.test(line)) break; // end of front matter
      if (/^\s*#/.test(line)) continue; // comment
      if (/^\s*-\s/.test(line)) continue; // list item
      const m = line.match(/^([A-Za-z0-9_\-]+):\s*(.*)$/);
      if (m) {
        const k = m[1];
        const c = keyCounts.get(k) || 0;
        keyCounts.set(k, c + 1);
      }
    }
    for (const [k, c] of keyCounts.entries()) {
      if (c > 1) {
        issues.push({ file: f, kind: 'frontmatter-duplicate-key', severity: 'error', msg: `Duplicate front-matter key: ${k}` });
      }
    }
  }
  // Skip generated/lint files already excluded
  const content = fm.content || '';
  const lines = content.split(/\r?\n/);
  // Headings are counted over PROSE only. A `# comment` on the first column of a fenced code
  // block is not a heading; counting it as one reported 31 phantom "Multiple H1" headings for
  // docs/how-to/use-ui.md and 31 for docs/reference/jseval-pipeline-reference.md
  // (2026-09-05, tempdoc 930 §22.2 follow-up 7).
  const prose = stripFencedCode(content);
  const proseLines = prose.split('\n');
  let idx = 0;
  while (idx < proseLines.length && /^\s*$/.test(proseLines[idx])) idx++;
  const first = proseLines[idx] || '';
  // Enforce: exactly one H1 and it must match front matter title.
  //
  // Scoped to DURABLE docs. `docs/tempdocs/**` is append-only dated working history whose H1
  // and `title` are read by no consumer: docs/llms.txt indexes only the canonical dirs
  // (scripts/docs/llmstxt-generate.mjs `canonicalDirs`), scripts/ci/lib/tempdoc-scan.mjs keys
  // on filenames and changeset frontmatter, and scripts/docs/tempdoc-staleness-triage.mjs
  // regexes `^title:` for classification keywords only. Enforcing an H1 shape there means
  // rewriting history to satisfy a rule nobody reads — 406 findings, zero consumers
  // (2026-09-05, tempdoc 930 §22.2 follow-up 7). Do not widen this without a consumer.
  const isWorkingHistory = f.startsWith('docs/tempdocs/');
  const h1Matches = isWorkingHistory ? [] : prose.match(/^#\s+(.+?)\s*$/gm) || [];
  if (!isWorkingHistory) {
    if (h1Matches.length === 0) {
      issues.push({ file: f, kind: 'heading', severity: 'error', msg: 'Missing top-level H1' });
    }
    if (h1Matches.length > 1) {
      issues.push({ file: f, kind: 'heading', severity: 'error', msg: `Multiple H1 headings (${h1Matches.length})` });
    }
    if (h1Matches.length === 1) {
      const h1Text = h1Matches[0].replace(/^#\s+/, '').trim();
      if (data.title && String(data.title).trim() !== h1Text) {
        issues.push({ file: f, kind: 'heading', severity: 'error', msg: `H1 does not match front matter title ('${data.title}' vs '${h1Text}')` });
      }
    }
    // The `heading-case` rule (Title Case for H1/H2) was DELETED in tempdoc 930. It produced
    // 6,751 findings repo-wide and was never honoured at any enforcement point — many headings
    // are intentionally lowercase. A rule nobody enforces is not a standard, it is noise that
    // hides this script's real findings. Do not reinstate it without an enforcement point.
    if (/^#\s+Untitled\s*$/.test(first)) {
      issues.push({ file: f, kind: 'heading', severity: 'error', msg: 'Top-level H1 is "Untitled"' });
    }
  }

  // Conditional requirements for normative docs
  const status = String(data.status || '').toLowerCase();
  if (status === 'normative') {
    const requiredFields = ['summary', 'audience', 'stability', 'version_introduced'];
    for (const req of requiredFields) {
      if (!data[req] || String(data[req]).trim() === '') {
        issues.push({ file: f, kind: 'meta', severity: 'error', msg: `Missing required field for normative doc: ${req}` });
      }
    }
    // Require key sections to exist
    const mustHaveHeadings = ['at a glance', 'constraints', 'interfaces', 'examples'];
    const text = content.toLowerCase();
    for (const h of mustHaveHeadings) {
      if (!new RegExp(`^##\\s+${h}($|\n)`, 'm').test(text)) {
        issues.push({ file: f, kind: 'structure', severity: 'error', msg: `Missing required heading: ## ${h}` });
      }
    }

    // Detect exact duplicate bullets within a MUSTs list block under At a glance
    for (let i = 0; i < lines.length; i++) {
      if (/^\s*-\s*MUSTs/i.test(lines[i])) {
        const seen = new Set();
        let j = i + 1;
        for (; j < lines.length; j++) {
          const m = lines[j].match(/^\s*-\s+(.*\S)\s*$/);
          if (!m) break;
          const item = m[1];
          if (seen.has(item)) {
            issues.push({ file: f, kind: 'musts-duplicate', severity: 'error', msg: `Duplicate MUST bullet: ${item}` });
          } else {
            seen.add(item);
          }
        }
      }
    }
  }

  // The `tags` and `aliases` soft rules ("expected at least 3 / at least 2") were DELETED on
  // 2026-09-05 (tempdoc 930 §22.2 follow-up 7). They asked every doc for frontmatter keys that
  // NOTHING in the repo reads: the llms.txt generator consumes `title`/`status`/`description`,
  // the adr-coverage enforcer `covers`/`status`/`last_reviewed`, world-state `last_reviewed`
  // — and not one file under docs/ carried a `tags:` or `aliases:`
  // key, so the rules fired on all 813 docs, forever. 1,626 warnings nobody could act on is
  // noise that hides this script's real findings. Do not reinstate without a consumer.
}

if (issues.length > 0) {
  const errors = issues.filter((i) => (i.severity || 'error') === 'error');
  const warns = issues.filter((i) => (i.severity || 'error') === 'warn');
  if (warns.length > 0) {
    console.warn('Docs validation warnings:');
    for (const i of warns) console.warn(`- ${i.file}: [${i.kind}] ${i.msg}`);
  }
  if (errors.length > 0) {
    console.error('Docs validation errors:');
    for (const i of errors) console.error(`- ${i.file}: [${i.kind}] ${i.msg}`);
    process.exit(1);
  }
}

execFileSync(process.execPath, ['scripts/docs/verify-runtime-config-matrix.mjs'], {
  stdio: 'inherit',
});
// tempdoc-status-check was retired (tempdoc 618 §14): its canonical-status set
// diverged from practice, it was never CI-wired, and it sat permanently red on
// ~20 tempdocs — an always-red, unread check hides real signal. The canonical
// status values remain documented as advisory guidance in docs/tempdocs/README.md.
console.log('Docs validation completed. No errors.');
