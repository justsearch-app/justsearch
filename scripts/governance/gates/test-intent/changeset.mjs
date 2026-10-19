/**
 * test-intent changesets: parsing, the acceptance digest, and the skeleton writer (tempdoc 966 D1).
 *
 * A changeset is gates/test-intent/.changesets/<name>.md:
 *
 *   ---                                   frontmatter (kernel parser, flat key: value)
 *   schema: test-intent.v1
 *   task: <task id>
 *   author-role: builder
 *   author-session: <the author's session id>
 *   pr: <PR number, optional; CI reads it from the event>
 *   ---
 *   prose ...
 *   ```json
 *   { "entries": [ ... ] }                exactly one json block before the acceptance heading
 *   ```
 *   ## Acceptance records                 everything from here on is EXCLUDED from the digest
 *   ```json
 *   { "kind": "acceptance", "role": "verifier", "session": "...", "verdict": "accept", "digest": "sha256:..." }
 *   ```
 *
 * The digest binds a record to the exact flagged content: every item the changeset covers (its
 * before and after content), the changeset text above the acceptance heading, and every evidence
 * file its entries cite. Line endings are normalised, so a checkout on another platform, a rebase
 * or a squash that leaves this content unchanged keeps the same digest.
 */

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

import { parseFrontmatter } from '../../lib/frontmatter.mjs';
import { ACCEPTANCE_HEADING, CHANGESET_SCHEMA, CHANGESETS_DIR, RULES_DOC } from './classifications.mjs';

export const sha256 = (text) => 'sha256:' + createHash('sha256').update(text, 'utf8').digest('hex');
export const normalizeText = (text) => text.replace(/\r\n?/g, '\n');

/** Split a changeset into the digested part and the acceptance-record part. */
export function splitChangeset(text) {
  const t = normalizeText(text);
  const re = /^## Acceptance records[ \t]*$/m;
  const m = re.exec(t);
  if (!m) return { digested: t.replace(/\s+$/, '') + '\n', records: '' };
  return { digested: t.slice(0, m.index).replace(/\s+$/, '') + '\n', records: t.slice(m.index) };
}

function jsonBlocks(text) {
  const blocks = [];
  const re = /^```json[ \t]*\n([\s\S]*?)^```[ \t]*$/gm;
  let m;
  while ((m = re.exec(text)) !== null) blocks.push(m[1]);
  return blocks;
}

/**
 * @returns {{ok: true, frontmatter, entries: object[], records: object[], digestedText: string}
 *          | {ok: false, error: string}}
 */
export function parseChangeset(text) {
  const parsed = parseFrontmatter(normalizeText(text));
  if (!parsed) return { ok: false, error: 'no frontmatter block (--- ... ---)' };
  const fm = parsed.frontmatter;
  if (fm.schema !== CHANGESET_SCHEMA) {
    return { ok: false, error: `frontmatter 'schema' must be '${CHANGESET_SCHEMA}' (got '${fm.schema ?? ''}')` };
  }
  const { digested, records: recordText } = splitChangeset(text);
  const entryBlocks = jsonBlocks(digested);
  if (entryBlocks.length !== 1) {
    return { ok: false, error: `expected exactly one \`\`\`json entries block above '${ACCEPTANCE_HEADING}', found ${entryBlocks.length}` };
  }
  let body;
  try {
    body = JSON.parse(entryBlocks[0]);
  } catch (e) {
    return { ok: false, error: `entries block is not valid JSON: ${e.message}` };
  }
  if (!body || !Array.isArray(body.entries)) return { ok: false, error: "entries block must be { \"entries\": [ ... ] }" };
  const records = [];
  for (const [i, block] of jsonBlocks(recordText).entries()) {
    try {
      records.push(JSON.parse(block));
    } catch (e) {
      return { ok: false, error: `acceptance record ${i + 1} is not valid JSON: ${e.message}` };
    }
  }
  return { ok: true, frontmatter: fm, entries: body.entries, records, digestedText: digested };
}

/** Items an entry covers. */
export function entryItems(entry) {
  if (Array.isArray(entry?.items)) return entry.items.filter((x) => typeof x === 'string');
  if (typeof entry?.item === 'string') return [entry.item];
  return [];
}

/**
 * The acceptance digest.
 *
 * @param {{changesetPath: string, digestedText: string,
 *          items: Array<{id: string, before: string|null, after: string|null}>,
 *          evidence: Array<{path: string, content: string|null}>}} input
 */
export function computeDigest({ changesetPath, digestedText, items, evidence }) {
  const lines = ['test-intent-digest.v1', `changeset ${changesetPath} ${sha256(normalizeText(digestedText))}`];
  for (const ev of [...evidence].sort((a, b) => cmp(a.path, b.path))) {
    lines.push(`evidence ${ev.path} ${ev.content === null ? '-' : sha256(normalizeText(ev.content))}`);
  }
  for (const it of [...items].sort((a, b) => cmp(a.id, b.id))) {
    lines.push(`item ${it.id} before=${it.before ?? '-'} after=${it.after ?? '-'}`);
  }
  return sha256(lines.join('\n') + '\n');
}

const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

const CHANGE_WORD = { A: 'added', D: 'deleted', M: 'modified' };

/** Skeleton entry for one flagged item; every value the author must fill starts with the unfilled marker (analyze.mjs rejects it). */
export function skeletonEntry(item) {
  return {
    items: [item.id],
    change: CHANGE_WORD[item.status] ?? item.status,
    kind: item.kind,
    class: 'TODO: New | Obsolete | Adaptation | Structural rule kept | Incidental | Defect pin',
    sources: [],
    note: `TODO: fill the fields the class needs (see ${RULES_DOC}); delete this note.`,
  };
}

/**
 * Write a skeleton changeset listing every given (uncovered) flagged item.
 *
 * @returns {string} the repo-relative path written
 */
export function writeSkeleton({ repoRoot, name, items, task = 'TODO', session = 'TODO' }) {
  const safe = name.replace(/[^A-Za-z0-9._-]/g, '-');
  let rel = `${CHANGESETS_DIR}/${safe}.md`;
  for (let n = 2; existsSync(resolve(repoRoot, rel)); n++) rel = `${CHANGESETS_DIR}/${safe}-${n}.md`;
  const entries = items.map(skeletonEntry);
  const text = [
    '---',
    `schema: ${CHANGESET_SCHEMA}`,
    `task: ${task}`,
    'author-role: builder',
    `author-session: ${session}`,
    '---',
    '',
    '# Test-intent entries',
    '',
    `Skeleton written by the test-intent gate. Rules: ${RULES_DOC}.`,
    'Give every entry a class and the fields that class needs; group items that share one warrant.',
    'The acceptor (not the author) appends the acceptance record under the heading at the end.',
    '',
    '```json',
    JSON.stringify({ entries }, null, 2),
    '```',
    '',
    ACCEPTANCE_HEADING,
    '',
  ].join('\n');
  const abs = resolve(repoRoot, rel);
  mkdirSync(dirname(abs), { recursive: true });
  writeFileSync(abs, text, 'utf8');
  return rel;
}

/** Append an acceptance record to a changeset's text (used by `cli.mjs accept` and the corpus). */
export function appendRecord(text, record) {
  const t = normalizeText(text).replace(/\s+$/, '');
  const head = /^## Acceptance records[ \t]*$/m.test(t) ? t : `${t}\n\n${ACCEPTANCE_HEADING}`;
  return `${head}\n\n\`\`\`json\n${JSON.stringify(record, null, 2)}\n\`\`\`\n`;
}
