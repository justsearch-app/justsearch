/**
 * Dead-config scanner — tempdoc 799 §O.3 / §N.2.f.1.
 *
 * Answers the question the count ratchet cannot: **does anything actually READ this setting?**
 *
 * Why this exists. Tempdoc 754 found 28 knobs that resolved, were documented, and were read by
 * nothing because a live consumer hardcoded the value — "a false promise to users". 799 removed 22
 * of them and added the count ratchet, and then demonstrated the gap in the most direct way
 * available: four keys survived that cleanup with their `EnvRegistry` declarations and YAML
 * contributions intact, the full unit suite was green, `build -x test` was green, and the count
 * ratchet was green — because a key that resolves but is never read is invisible to all of them.
 * Only inspecting the running backend's own config surface exposed it (799 §N.2.f.1).
 *
 * A setting is considered READ if ANY of three paths reaches it. All three are real and in use, and
 * a scanner that knows only the first would fire ~30 false positives on this repo — which is how a
 * gate gets switched off (799 §O.1's failure mode) rather than obeyed:
 *
 *   1. it is resolved into a {@code ResolvedConfig} record component that some production file
 *      calls the accessor for;
 *   2. it is read directly through its {@code EnvRegistry} enum constant
 *      ({@code EnvRegistry.SOME_KEY.getString(...)});
 *   3. its raw key string appears anywhere in production sources outside the configuration module.
 *
 * Deliberately NOT a whole-program analysis: it is a string-level scan. Reflection or a computed
 * key name would be a false positive; none exist in this repo today, and the baseline absorbs any
 * that appear.
 *
 * KNOWN LIMITATION — bare-name collision can MASK a dead component (tempdoc 799 §Q). The
 * `unreadComponents` half matches a bare `.name(`, so an unrelated call on a different type counts
 * as a read. Live instance: `ResolvedConfig.llm()` is never called in production (the only
 * non-declaration `.llm()` is `LlmSettingsV2.llm()` at SettingsController.java:200, a different
 * type), so the ENTIRE `Llm` record is unreachable — yet only `simulatedLatencyMs`, the one
 * uniquely-named component, is reported. Roughly ten documented settings behind that record are
 * therefore false promises this scanner does not catch, including `JUSTSEARCH_LLM_MIN_P` and
 * `JUSTSEARCH_LLM_RNG_SEED` (environment-variables.md:102-103).
 *
 * Two disambiguating heuristics were tried and REJECTED, both falsified against real code:
 * requiring the chained `.parent().component()` form (defeated — `hybridSearch()` is consumed only
 * via assignment), and requiring the containing file to mention `ResolvedConfig` (defeated —
 * SettingsController mentions it incidentally). Precision here needs type information a string
 * scan does not have. An honest limitation beats a heuristic that misfires: a gate that cries wolf
 * gets switched off, which is the §O.1 failure mode this whole tempdoc is about.
 *
 * So: this scanner catches the dead-KEY class reliably and the dead-COMPONENT class only when the
 * name is unique. Do not read a green result as "the config surface is clean".
 */

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

// Only the three DECLARATION files are excluded from the reader corpus. A key naming itself in
// its own declaration is not a reader — but everything else, including other files inside the
// configuration package, must stay visible.
//
// Tempdoc 799 §Q: this previously excluded the whole `/io/justsearch/configuration/` package,
// which made every in-package reader invisible BY CONSTRUCTION and produced a false positive —
// `justsearch.fieldCatalog` is read at JustSearchConfigurationLoader.java:116 (called from
// KnowledgeServer) and was reported as a false promise. An adversarial review caught it.
const DECLARATION_FILES = [
  '/io/justsearch/configuration/EnvRegistry.java',
  '/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java',
  '/io/justsearch/configuration/resolved/ResolvedConfig.java',
];
const PRIMITIVES = new Set(['int', 'long', 'double', 'boolean', 'float', 'short', 'byte', 'char']);

export function collectJavaMainSources(root) {
  const out = [];
  const modules = resolve(root, 'modules');
  if (!existsSync(modules)) return out;
  const walk = (dir) => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const p = join(dir, e.name);
      if (e.isDirectory()) {
        if (!/^(build|node_modules|\.git|\.gradle|bin|out)$/.test(e.name)) walk(p);
      } else if (e.name.endsWith('.java') && p.replace(/\\/g, '/').includes('/src/main/')) {
        out.push(p);
      }
    }
  };
  walk(modules);
  return out;
}

/** Record components declared across every `public record` in ResolvedConfig. */
export function parseRecordComponents(resolvedConfigSource) {
  const comps = new Set();
  for (const m of resolvedConfigSource.matchAll(/public record (\w+)\(([\s\S]*?)\)\s*\{/g)) {
    for (const raw of m[2].split(',')) {
      const tokens = raw.trim().split(/\s+/);
      if (tokens.length < 2) continue;
      const name = tokens[tokens.length - 1];
      const type = tokens[tokens.length - 2];
      if (!/^[a-z][A-Za-z0-9]*$/.test(name)) continue;
      if (!(PRIMITIVES.has(type) || /^[A-Z]/.test(type) || type.endsWith('>'))) continue;
      comps.add(name);
    }
  }
  return comps;
}

/**
 * @returns {{deadKeys: string[], unreadComponents: string[], totals: {keys: number, components: number}}}
 */
export function scanDeadConfig(sourceRoot) {
  const cfg = resolve(sourceRoot, 'modules/configuration/src/main/java/io/justsearch/configuration');
  const rcPath = join(cfg, 'resolved', 'ResolvedConfig.java');
  const rcbPath = join(cfg, 'resolved', 'ResolvedConfigBuilder.java');
  const erPath = join(cfg, 'EnvRegistry.java');
  if (!existsSync(rcPath) || !existsSync(rcbPath) || !existsSync(erPath)) {
    return { deadKeys: [], unreadComponents: [], totals: { keys: 0, components: 0 }, skipped: true };
  }

  const rc = readFileSync(rcPath, 'utf8');
  const rcb = readFileSync(rcbPath, 'utf8');
  const er = readFileSync(erPath, 'utf8');

  const files = collectJavaMainSources(sourceRoot);
  const allSources = files.map((f) => readFileSync(f, 'utf8')).join('\n');
  const outsideConfig = files
    .filter((f) => {
      const n = f.replace(/\\/g, '/');
      return !DECLARATION_FILES.some((d) => n.endsWith(d));
    })
    .map((f) => readFileSync(f, 'utf8'))
    .join('\n');

  const components = parseRecordComponents(rc);
  const unreadComponents = [...components]
    .filter((c) => !new RegExp('\\.' + c + '\\s*\\(').test(allSources))
    .sort();

  const keyToConst = new Map();
  for (const m of er.matchAll(/([A-Z][A-Z0-9_]*)\(\s*"([a-z][\w.]+)"\s*,\s*"[A-Z_][A-Z0-9_]*"/g)) {
    keyToConst.set(m[2], m[1]);
  }
  const declared = new Set(keyToConst.keys());
  for (const m of rcb.matchAll(/putYaml\w*\(\s*"([^"]+)"/g)) declared.add(m[1]);
  const resolvedKeys = new Set();
  for (const m of rcb.matchAll(/resolve\w*\(\s*"([^"]+)"/g)) resolvedKeys.add(m[1]);

  const deadKeys = [];
  for (const key of [...declared].sort()) {
    if (resolvedKeys.has(key)) continue; // path 1
    const constant = keyToConst.get(key);
    if (constant && new RegExp('EnvRegistry\\.' + constant + '\\b').test(outsideConfig)) continue; // path 2
    if (outsideConfig.includes('"' + key + '"')) continue; // path 3
    deadKeys.push(key);
  }

  return {
    deadKeys,
    unreadComponents,
    totals: { keys: declared.size, components: components.size },
  };
}
