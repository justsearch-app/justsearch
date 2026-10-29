/**
 * YAML-reader scanner — tempdoc 883 decision 5 (lane A, PR 3).
 *
 * The sibling {@link ./dead-config.mjs} answers "is this DECLARED setting read by anything?".
 * It cannot answer the mirror question, because it never looks at the YAML file at all: its
 * `declared` set is built from `EnvRegistry` constants plus the FIRST argument of every
 * `putYaml*` call. A key that exists in `config/application.yaml` and is contributed by nothing
 * is therefore invisible to it — the file is shipped, the operator edits it, and the value goes
 * nowhere.
 *
 * That is not hypothetical. Tempdoc 882 (lane 0) found `search.pipeline.profile` and
 * `index.pipeline.profile` sitting in the shipped YAML while the resolver only ever resolves the
 * sysprop spelling `justsearch.search.pipeline.profile` (`ResolvedConfigBuilder.java:1403`,
 * `EnvRegistry.java:258`). Editing the YAML did nothing, silently. Nothing in `scripts/` parsed
 * `application.yaml` before this file, so no check could have caught it.
 *
 * WHAT COUNTS AS A READER. `putYaml*(key, root, yamlPath)` takes the YAML path as its THIRD
 * argument and the resolver key as its first; they are usually but not always the same string.
 * Some groups are also walked by hand (`root.path("index").path("ocr").path("languages")`).
 * A YAML key is considered read when any of these holds — for the key itself OR for any ancestor
 * prefix of it, because reading a parent node reads everything under it:
 *
 *   1. its dotted path appears as a string literal in `ResolvedConfigBuilder.java`
 *      (covers both the `putYaml*` yamlPath argument and the resolver-key argument);
 *   2. its segments appear as a `.path("a").path("b")` chain in `ResolvedConfigBuilder.java`
 *      (covers the hand-walked groups);
 *   3. its dotted path appears as a string literal anywhere in production Java outside the three
 *      configuration DECLARATION files — the same "path 3" the dead-config scanner uses, for the
 *      same reason: a consumer may name the key directly.
 *
 * Deliberately a string-level scan, like its sibling. A computed YAML path would be a false
 * positive; none exist today, and the baseline absorbs any that appear.
 *
 * HONEST LIMIT — the ancestor rule is deliberately generous. `root.path("search")` marks every
 * key under `search:` as read, which is right for a hand-walked group and wrong for a parent that
 * is walked for one child only. Precision here needs to know which leaf the walk reaches, which a
 * string scan does not. The failure direction is a MISSED dead key, never a false alarm — chosen
 * because a gate that cries wolf gets switched off (tempdoc 799 §O.1), which is the failure this
 * whole gate family exists to avoid.
 */

import { readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

import yaml from 'js-yaml';
import { collectJavaMainSources } from './dead-config.mjs';

/** Same three files the dead-config scanner excludes from the reader corpus, for the same reason. */
const DECLARATION_FILES = [
  '/io/justsearch/configuration/EnvRegistry.java',
  '/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java',
  '/io/justsearch/configuration/resolved/ResolvedConfig.java',
];

/**
 * Flatten a parsed YAML document to the set of dotted paths that carry a VALUE.
 *
 * A `null` value (`key:` with nothing under it) is a comment-shaped placeholder in this file, not
 * a setting — `app:` carries only comments — so it is not reported. An empty list IS a value
 * (`index.ocr.languages: []` means "no languages", which the resolver reads).
 */
export function flattenYamlKeys(doc, prefix = '', out = new Set()) {
  if (doc === null || doc === undefined) return out;
  if (Array.isArray(doc) || typeof doc !== 'object') {
    if (prefix) out.add(prefix);
    return out;
  }
  for (const [k, v] of Object.entries(doc)) {
    const path = prefix ? `${prefix}.${k}` : k;
    if (v !== null && v !== undefined && typeof v === 'object' && !Array.isArray(v)) {
      flattenYamlKeys(v, path, out);
    } else if (v !== null && v !== undefined) {
      out.add(path);
    }
  }
  return out;
}

/**
 * Every dotted YAML path a `putYaml*(key, node, yamlPath)` call actually contributes.
 *
 * The third argument is relative to the JsonNode in the second, and the group helpers bind that
 * node to a local first: `JsonNode searchRoot = root.path("search");` then
 * `putYamlFromNode("search.hybrid.bm25_k", searchRoot, "hybrid.bm25_k")`. Reading only the string
 * literals happens to work for the groups whose resolver key spells the absolute path — and
 * silently fails for the ones that do not, which is exactly the class this scanner exists to catch.
 * So the assignments are resolved and the relative path is rejoined.
 */
export function extractPutYamlPaths(source) {
  const nodePrefix = new Map([['root', '']]);
  for (const m of source.matchAll(/JsonNode\s+(\w+)\s*=\s*root\.path\(\s*"([\w.-]+)"\s*\)\s*;/g)) {
    nodePrefix.set(m[1], m[2]);
  }
  const paths = new Set();
  for (const m of source.matchAll(
    /putYaml\w*\(\s*"[^"]*"\s*,\s*(\w+)\s*,\s*\n?\s*"([\w.-]+)"/g,
  )) {
    const prefix = nodePrefix.get(m[1]);
    if (prefix === undefined) continue; // unresolvable node expression — fall back to the literal rule
    paths.add(prefix ? `${prefix}.${m[2]}` : m[2]);
  }
  return paths;
}

/** Every `.path("a").path("b")…` chain in a source, as dotted strings. */
export function extractPathChains(source) {
  const chains = new Set();
  for (const m of source.matchAll(/(?:\.path\(\s*"[\w.-]+"\s*\)\s*){2,}/g)) {
    const segs = [...m[0].matchAll(/\.path\(\s*"([\w.-]+)"\s*\)/g)].map((s) => s[1]);
    // Every MULTI-segment prefix of the chain is also reached by the walk: code that walks to
    // `index.ocr` and iterates its children reads everything under it. Single-segment prefixes
    // are excluded — treating a walk into `index` as reading all of `index.*` would make the
    // top-level groups unfalsifiable, which is exactly the blind spot this scanner exists to close.
    for (let i = 2; i <= segs.length; i++) chains.add(segs.slice(0, i).join('.'));
  }
  return chains;
}

/** Ancestor prefixes of `a.b.c`, longest first: `a.b.c`, `a.b`, `a`. */
function prefixesOf(key) {
  const segs = key.split('.');
  const out = [];
  for (let i = segs.length; i >= 1; i--) out.push(segs.slice(0, i).join('.'));
  return out;
}

/**
 * @returns {{unreadYamlKeys: string[], totals: {yamlKeys: number}, skipped?: boolean}}
 */
export function scanYamlReaders(sourceRoot, yamlRelPath = 'config/application.yaml') {
  const yamlPath = resolve(sourceRoot, yamlRelPath);
  const rcbPath = resolve(
    sourceRoot,
    'modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java',
  );
  if (!existsSync(yamlPath) || !existsSync(rcbPath)) {
    return { unreadYamlKeys: [], totals: { yamlKeys: 0 }, skipped: true };
  }

  let doc;
  try {
    doc = yaml.load(readFileSync(yamlPath, 'utf8'));
  } catch (e) {
    return { unreadYamlKeys: [], totals: { yamlKeys: 0 }, parseError: e.message };
  }
  const keys = [...flattenYamlKeys(doc)].sort();

  const rcb = readFileSync(rcbPath, 'utf8');
  const chains = extractPathChains(rcb);
  const contributed = extractPutYamlPaths(rcb);

  const files = collectJavaMainSources(sourceRoot);
  const outsideConfig = files
    .filter((f) => {
      const n = f.replace(/\\/g, '/');
      return !DECLARATION_FILES.some((d) => n.endsWith(d));
    })
    .map((f) => readFileSync(f, 'utf8'))
    .join('\n');

  const unreadYamlKeys = [];
  for (const key of keys) {
    // A string LITERAL only counts for the exact key. An ancestor literal does not: `"search"`
    // occurs in Java for a hundred reasons that have nothing to do with reading a YAML group, and
    // accepting it marked every key in the file read (measured while writing this scanner — the
    // two keys 882 found dead came back green). A `.path()` CHAIN is different: it is unambiguously
    // a walk into this document, so its ancestors do count.
    const literal = rcb.includes(`"${key}"`) || outsideConfig.includes(`"${key}"`);
    const walked = prefixesOf(key).some((p) => chains.has(p) || contributed.has(p));
    if (!literal && !walked) unreadYamlKeys.push(key);
  }

  return { unreadYamlKeys, totals: { yamlKeys: keys.length } };
}
