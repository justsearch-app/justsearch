#!/usr/bin/env node
/**
 * language-agnostic-analysis gate — tempdoc 581 / ADR-0043 (native multilingual, no per-language
 * levers). The rung-2 backstop on tempdoc 576's enforcement ladder: it forbids reintroducing any
 * per-language ANALYSIS artifact. The rung-1 half is the closed analyzer-provider `enum` in
 * `SSOT/schemas/indexing/analyzers-catalog.schema.json` (a per-language provider is unrepresentable
 * there); this gate covers what the schema cannot express:
 *
 *   1. Every analyzer is locale-invariant (`locale: "*"`) with a provider in the language-agnostic
 *      set — so there is no per-language analyzer slot to grow.
 *   2. No locale-suffixed `content_<lang>` field in the field catalog (e.g. content_en/content_de).
 *   3. No NON-EMPTY per-language synonym / dictionary file under the catalogs dir (hand-curated
 *      per-language synonym sets are a per-language lever).
 *   4. The query path references no `content_<lang>` field literal (no per-language field routing).
 *   5. The query path contains no authored Set.of/List.of natural-language word list.
 *
 * A NEW language must require NO new authored artifact (581 §1). SCOPE IS THE SEARCH ENGINE ONLY —
 * UI localization (SSOT/messages, SSOT/prompts, i18n) is a separate, legitimately per-language
 * surface and is out of scope (581 §12.7). Honest limit: query routing through a computed/non-literal
 * field name is import-invisible to this scan — register + discipline, not absolute.
 *
 * Lighter scripts/ci tier; wired as a ci.yml step + the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md).
 */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { stripComments as stripCommentsShared } from '../lib/strip-comments.mjs';
import { scanAuthoredWordLists } from './lib/language-agnostic-word-lists.mjs';

const REGISTER = 'governance/language-agnostic-analysis.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const norm = (p) => p.replace(/\\/g, '/');
const stripComments = (s) => stripCommentsShared(s, { withHtml: false });

const failures = [];
const allowedProviders = new Set(reg.allowedProviders);
const localeFieldRe = new RegExp(reg.localeFieldPattern);
const queryCodeScopes = reg.queryCodeScopes ?? [reg.queryCodeScope].filter(Boolean);

// 1. Analyzers: locale-invariant + language-agnostic provider (no per-language analyzer).
{
  const analyzers = JSON.parse(readFileSync(reg.analyzersCatalog, 'utf8')).analyzers || [];
  for (const a of analyzers) {
    if (a.locale !== '*') {
      failures.push(
        `${reg.analyzersCatalog}: analyzer '${a.id}' has per-language locale '${a.locale}' — analysis ` +
          `must be locale-invariant (locale '*'). A per-language analyzer is the lever 581/ADR-0043 forbids.`,
      );
    }
    if (!allowedProviders.has(a.provider)) {
      failures.push(
        `${reg.analyzersCatalog}: analyzer '${a.id}' provider '${a.provider}' is not in the ` +
          `language-agnostic set {${[...allowedProviders].join(', ')}} — no per-language stemmer/stopwords/synonyms.`,
      );
    }
  }
}

// 5. Query path: no authored natural-language word lists. This catches the literal shape used by
// the retired English STOP_WORDS gate while leaving field-name/status-code collections alone.
{
  const minEntries = Number(reg.authoredWordListMinEntries || 6);
  failures.push(
    ...scanAuthoredWordLists({
      scopes: queryCodeScopes,
      minEntries,
      naturalLanguageWordPattern: reg.naturalLanguageWordPattern,
    }),
  );
}

// 2. Fields: no locale-suffixed content_<lang> field (route every language through `content`).
{
  const fields = JSON.parse(readFileSync(reg.fieldsCatalog, 'utf8')).fields || [];
  for (const f of fields) {
    if (localeFieldRe.test(f.id)) {
      failures.push(
        `${reg.fieldsCatalog}: field '${f.id}' is a per-language locale content field — route all ` +
          `languages through 'content'; locale-aware content fields are the lever 581/ADR-0043 forbids.`,
      );
    }
  }
}

// 3. Synonyms: any synonyms.* file under the catalogs dir must be empty (no non-comment rows).
if (existsSync(reg.synonymDir)) {
  for (const name of readdirSync(reg.synonymDir)) {
    if (!/^synonyms\./.test(name)) continue;
    const rows = readFileSync(join(reg.synonymDir, name), 'utf8')
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter((l) => l && !l.startsWith('#'));
    if (rows.length > 0) {
      failures.push(
        `${norm(join(reg.synonymDir, name))}: non-empty per-language synonym file (${rows.length} rows) — ` +
          `hand-curated per-language synonym sets are a per-language lever (581/ADR-0043).`,
      );
    }
  }
}

// 4. Query path: no content_<lang> field literal (no per-language field routing).
{
  const litRe = /"content_[a-z]{2}"/;
  const walk = (dir, acc) => {
    if (!existsSync(dir)) return acc;
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      const st = statSync(p);
      if (st.isDirectory()) walk(p, acc);
      else if (/\.java$/.test(name)) acc.push(p);
    }
    return acc;
  };
  for (const scope of queryCodeScopes) {
    for (const f of walk(scope, [])) {
      const src = stripComments(readFileSync(f, 'utf8'));
      if (litRe.test(src)) {
        failures.push(
          `${norm(f)}: references a content_<lang> field literal — query routing to a per-language ` +
            `analyzer field is forbidden (581/ADR-0043). Query 'content' (locale-invariant) only.`,
        );
      }
    }
  }
}

if (failures.length > 0) {
  console.error(
    '✗ language-agnostic-analysis gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'),
  );
  process.exit(1);
}
console.log(
  '✓ language-agnostic-analysis gate OK — analysis is locale-invariant; no per-language analyzer, ' +
    'content_<lang> field, non-empty synonym file, per-language field routing, or authored query ' +
      'word list (581/ADR-0043).',
);
