/**
 * Build configuration that decides how tests are selected or run — tempdoc 966 D1 (S2, weakenings
 * that never touch an assertion line).
 *
 * An expectation can leave CI through the build alone: a tag excluded unconditionally, a Gradle test
 * filter, a vitest include/exclude glob, a test script that stops running a selection. The gate
 * watches these files and flags one as an item when its changed lines (added or removed) touch test
 * selection or execution:
 *
 *   gradle      *.gradle.kts / *.gradle anywhere, and build-logic/** (Kotlin convention plugins).
 *               A changed code line counts when (a) it names test selection or execution itself
 *               (an identifier part such as test, tags, junit, retry, timeout, fork, suite, stress,
 *               experiment), (b) it lies inside a block whose header does (`withType<Test>`,
 *               `tasks.named<Test>("test")`, `useJUnitPlatform {`, `testing {`, ...), or (c) it
 *               declares a value (`val x = ...`) that a line of such a block reads. Dependency
 *               declarations (`testImplementation(...)`, `implementation(...)`) are not (a) on their
 *               own: a version bump is not a selection change. Comment-only lines never count.
 *   properties  gradle.properties: a changed line whose key or value names tests, tags, stress,
 *               experiment, retries, timeouts or forks.
 *   js-config   vite.config.*: the same rules as gradle, with the vitest `test: {` block as context.
 *   test-runner vitest.config*.*, vitest.workspace.*, playwright.config.*: every change counts.
 *   package     package.json: the test scripts (name or command naming a test runner or a test
 *               selection) and the runner config keys (vitest, jest, mocha, c8, nyc, playwright)
 *               are compared as a projection; any difference counts.
 *
 * On doubt the file is flagged: a file whose braces do not balance, or a package.json that does not
 * parse, counts as changed. scripts/** is governance tooling and out of scope (OUT_OF_SCOPE_STATEMENT).
 */

/** Identifier parts that name test selection or execution. */
const TEST_PARTS = new Set([
  'test', 'tests', 'testing', 'junit', 'jupiter', 'testng', 'tag', 'tags', 'stress', 'experiment',
  'retry', 'retries', 'timeout', 'timeouts', 'fork', 'forks', 'suite', 'suites', 'vitest', 'playwright',
  'e2e', 'spec', 'specs', 'jest', 'mocha',
]);

/** A whole line that only declares a dependency (its version bump is not a selection change). */
const DEPENDENCY_LINE = /^\s*(?:(?:"?[A-Za-z]*(?:[Ii]mplementation|[Rr]untimeOnly|[Cc]ompileOnly|[Aa]pi|[Aa]nnotationProcessor|[Kk]apt|[Kk]sp)"?|classpath|platform|enforcedPlatform)\s*\(|add\(\s*"[A-Za-z]*(?:Implementation|RuntimeOnly|CompileOnly|Api|AnnotationProcessor)"\s*,)/;

/** The first line of a Gradle up-to-date declaration (`inputs.file(...)`, `outputs.dir(...)`). */
const INPUTS_OUTPUTS_STATEMENT = /^\s*(?:this\.)?(?:inputs|outputs)\b/;

/** vitest's `test:` block in a vite config. */
const JS_TEST_KEY = /(^|[\s{,])test\s*:/;

/** Split code into identifier parts: camelCase, snake_case, kebab-case and dotted names. */
export function identifierParts(code) {
  const out = [];
  for (const word of code.match(/[A-Za-z][A-Za-z0-9]*/g) ?? []) {
    for (const part of word.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2').split(' ')) {
      out.push(part.toLowerCase());
    }
  }
  return out;
}

/** Test framework names that camelCase splitting breaks apart (`useJUnitPlatform` -> use, j, unit). */
const TEST_FRAMEWORK_NAME = /junit|testng/i;

function namesTestExecution(code, { js = false } = {}) {
  if (js && JS_TEST_KEY.test(code)) return true;
  if (DEPENDENCY_LINE.test(code)) return false;
  return TEST_FRAMEWORK_NAME.test(code) || identifierParts(code).some((p) => TEST_PARTS.has(p));
}

/**
 * Per-line view of a Kotlin/Groovy/JS source: the code with comments removed (strings kept, so
 * `excludeTags("windows")` still reads as such), the blocks open at the line, and the blocks the line
 * opens and closes. Braces inside strings, templates, character literals, comments and JS regex
 * literals are ignored.
 *
 * @returns {{lines: Array<{code: string, headers: string[], blocks: number[], opens: number[],
 *            closes: number[], parenAtStart: number}>, headerOf: Map<number, string>, balanced: boolean}}
 */
export function scanBlocks(text, { js = false } = {}) {
  const src = text.replace(/\r\n?/g, '\n');
  const lines = [];
  const headerOf = new Map();
  const stack = [];
  let nextId = 0;
  let balanced = true;
  let code = '';
  let blocksAtStart = [];
  let opens = [];
  let closes = [];
  let paren = 0;
  let parenAtStart = 0;
  let lastCodeLine = '';
  let state = 'code';
  let quote = '';
  let lastSignificant = '';
  const endLine = () => {
    lines.push({
      code: code.trimEnd(),
      headers: [...blocksAtStart, ...opens].map((id) => headerOf.get(id)),
      blocks: blocksAtStart,
      opens,
      closes,
      parenAtStart,
    });
    if (code.trim()) lastCodeLine = code.trim();
    code = '';
    opens = [];
    closes = [];
    blocksAtStart = [...stack];
    parenAtStart = paren;
  };
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    const next = src[i + 1];
    if (ch === '\n') {
      if (state === 'line' || state === 'regex' || state === 'regex-class') state = 'code';
      if (state === 'string' && quote !== '"""' && quote !== '`') state = 'code'; // unterminated: recover
      endLine();
      continue;
    }
    if (state === 'line') continue;
    if (state === 'block') {
      if (ch === '*' && next === '/') {
        state = 'code';
        i++;
      }
      continue;
    }
    if (state === 'string' || state === 'regex' || state === 'regex-class') {
      code += ch;
      if (ch === '\\') {
        if (next !== undefined && next !== '\n') {
          code += next;
          i++;
        }
        continue;
      }
      if (state === 'string') {
        if (quote === '"""' && src.startsWith('"""', i)) {
          code += '""';
          i += 2;
          state = 'code';
        } else if (quote !== '"""' && ch === quote) {
          state = 'code';
        }
      } else if (state === 'regex') {
        if (ch === '[') state = 'regex-class';
        else if (ch === '/') state = 'code';
      } else if (ch === ']') {
        state = 'regex';
      }
      continue;
    }
    // code
    if (ch === '/' && next === '/') {
      state = 'line';
      i++;
      continue;
    }
    if (ch === '/' && next === '*') {
      state = 'block';
      i++;
      continue;
    }
    if (js && ch === '/' && (lastSignificant === '' || /[(,=:[!&|?{};+\-*%<>~^]/.test(lastSignificant))) {
      state = 'regex';
      code += ch;
      continue;
    }
    if (ch === '"' && src.startsWith('"""', i) && !js) {
      state = 'string';
      quote = '"""';
      code += '"""';
      i += 2;
      lastSignificant = '"';
      continue;
    }
    if (ch === '"' || ch === "'" || (js && ch === '`')) {
      state = 'string';
      quote = ch;
      code += ch;
      lastSignificant = ch;
      continue;
    }
    if (ch === '{') {
      const id = nextId++;
      headerOf.set(id, code.trim() || lastCodeLine);
      stack.push(id);
      opens.push(id);
    } else if (ch === '}') {
      if (stack.length === 0) balanced = false;
      else closes.push(stack.pop());
    } else if (ch === '(' || ch === '[') {
      paren++;
    } else if ((ch === ')' || ch === ']') && paren > 0) {
      paren--;
    }
    code += ch;
    if (!/\s/.test(ch)) lastSignificant = ch;
  }
  endLine();
  if (stack.length !== 0 || state === 'block' || state === 'string') balanced = false;
  return { lines, headerOf, balanced };
}

/** Indices of removed lines of `a` and added lines of `b` (LCS on lines, common ends trimmed). */
export function changedLineIndices(a, b) {
  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) start++;
  let endA = a.length;
  let endB = b.length;
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA--;
    endB--;
  }
  const midA = a.slice(start, endA);
  const midB = b.slice(start, endB);
  const removed = [];
  const added = [];
  if (midA.length * midB.length > 4_000_000) {
    for (let i = start; i < endA; i++) removed.push(i);
    for (let j = start; j < endB; j++) added.push(j);
    return { removed, added };
  }
  const n = midA.length;
  const m = midB.length;
  const dp = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = midA[i] === midB[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (midA[i] === midB[j]) {
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      removed.push(start + i++);
    } else {
      added.push(start + j++);
    }
  }
  while (i < n) removed.push(start + i++);
  while (j < m) added.push(start + j++);
  return { removed, added };
}

/** Which watched build file this is, or null. */
export function buildConfigKind(rel) {
  const p = rel.replaceAll('\\', '/');
  if (p.startsWith('scripts/') || p.split('/').includes('node_modules')) return null;
  const base = p.slice(p.lastIndexOf('/') + 1);
  if (/\.gradle(\.kts)?$/.test(base)) return 'gradle';
  if (p.startsWith('build-logic/') && /\.(kt|kts|java|groovy)$/.test(base)) return 'gradle';
  if (base === 'gradle.properties') return 'properties';
  if (/^(vitest\.config|vitest\.workspace|playwright\.config)(\.[A-Za-z0-9-]+)*\.(js|mjs|cjs|ts|mts|cts|json)$/.test(base)) return 'test-runner';
  if (/^vite\.config(\.[A-Za-z0-9-]+)*\.(js|mjs|cjs|ts|mts|cts)$/.test(base)) return 'js-config';
  if (base === 'package.json') return 'package';
  return null;
}

/**
 * The first line of the statement a line belongs to: continuation lines (inside open parentheses,
 * or starting with `.` / `?.`) belong to the statement above them.
 */
function statementStart(scan, idx) {
  let i = idx;
  while (i > 0) {
    const l = scan.lines[i];
    if (l.parenAtStart > 0 || /^\s*\??\./.test(l.code) || !l.code.trim()) i--;
    else break;
  }
  return i;
}

/** A line that only closes blocks or brackets (`}`, `})`, `},`). */
const CLOSER_ONLY = /^[\s})\];,]*$/;

/**
 * Lines of one side that touch test selection or execution, as {line, code, why} (1-based).
 *
 * Not counted: comment-only lines; Gradle up-to-date declarations (`inputs.*`, `outputs.*` and their
 * continuation lines), which decide whether a cached result is reused, not which tests run or how;
 * dependency declarations, even inside a test suite's `dependencies {}`. A line that only opens a
 * block named after tests (`tasks.named<Test>("test") {`) or only closes one counts when a counted
 * line inside that block changed, or when nothing else inside it changed (a renamed task header
 * moves its whole configuration and counts).
 */
function testishLines(scan, indices, { js }) {
  const isTestHeader = (id) => namesTestExecution(scan.headerOf.get(id) ?? '', { js });
  const inContext = (l) => l.blocks.some(isTestHeader);
  // What test configuration reads: block headers that name tests, and every line inside such a block.
  const contextText = scan.lines.filter((l) => inContext(l) || l.headers.some((h) => namesTestExecution(h, { js })))
    .map((l) => l.code).join('\n');
  const skipped = (idx) => {
    const start = scan.lines[statementStart(scan, idx)].code;
    return (!js && INPUTS_OUTPUTS_STATEMENT.test(start)) || DEPENDENCY_LINE.test(start);
  };

  const counted = [];
  const deferred = [];
  const changedContent = [];
  for (const idx of indices) {
    const l = scan.lines[idx];
    if (!l || !l.code.trim()) continue;
    const code = l.code.trim();
    if (CLOSER_ONLY.test(code)) {
      const block = l.closes[0] ?? l.blocks[l.blocks.length - 1];
      if (block !== undefined && (isTestHeader(block) || inContext(l))) {
        deferred.push({ idx, block, why: 'closes a test task or test configuration block' });
      }
      continue;
    }
    changedContent.push(idx);
    if (skipped(idx)) continue;
    const opener = l.opens.length > 0 && code.endsWith('{') ? l.opens[l.opens.length - 1] : undefined;
    if (opener !== undefined && !inContext(l) && namesTestExecution(code, { js })) {
      deferred.push({ idx, block: opener, why: 'opens a test task or test configuration block' });
      continue;
    }
    let why = null;
    if (namesTestExecution(code, { js })) why = 'names test selection or execution';
    else if (inContext(l) || l.opens.some(isTestHeader)) why = 'inside a test task or test configuration block';
    else {
      const decl = /\b(?:val|var|let|const)\s+([A-Za-z_$][A-Za-z0-9_$]*)/.exec(code);
      if (decl && new RegExp(`(^|[^A-Za-z0-9_$])${decl[1].replace(/\$/g, '\\$')}([^A-Za-z0-9_$]|$)`).test(contextText)) {
        why = `declares '${decl[1]}', which test configuration reads`;
      }
    }
    if (why) counted.push({ idx, why });
  }
  const within = (idx, block) => idx !== undefined && scan.lines[idx].blocks.includes(block);
  for (const d of deferred) {
    const anyChanged = changedContent.some((i) => i !== d.idx && within(i, d.block));
    const anyCounted = counted.some((c) => within(c.idx, d.block));
    if (anyCounted || !anyChanged) counted.push(d);
  }
  return counted.sort((x, y) => x.idx - y.idx).map((c) => ({ line: c.idx + 1, code: scan.lines[c.idx].code.trim(), why: c.why }));
}

const PACKAGE_RUNNER_KEYS = ['vitest', 'jest', 'mocha', 'c8', 'nyc', 'playwright', 'ava'];

/** A command that reaches the product test suite (modules/, Gradle, or a frontend test runner). */
const PRODUCT_TEST_COMMAND = /(^|[^A-Za-z0-9_])(modules\/|gradlew|vitest|playwright|jest|mocha)/;

/**
 * The test-related projection of a package.json (throws on invalid JSON).
 *
 * Under modules/ a script counts when its name or command names tests. Elsewhere (the root, packages/)
 * the product test suite is reached only through a command that runs modules/, Gradle or a frontend
 * test runner; scripts that run governance tooling under scripts/ are out of scope (D1).
 */
export function packageTestProjection(text, { productModule = true } = {}) {
  const pkg = JSON.parse(text);
  const scripts = pkg && typeof pkg.scripts === 'object' && pkg.scripts ? pkg.scripts : {};
  const selected = Object.keys(scripts).sort()
    .filter((name) => (productModule
      ? identifierParts(name).some((p) => TEST_PARTS.has(p)) || identifierParts(String(scripts[name])).some((p) => TEST_PARTS.has(p))
      : PRODUCT_TEST_COMMAND.test(String(scripts[name]))))
    .map((name) => [name, scripts[name]]);
  const runners = PACKAGE_RUNNER_KEYS.filter((k) => pkg?.[k] !== undefined).map((k) => [k, pkg[k]]);
  return JSON.stringify({ scripts: selected, runners });
}

/**
 * Does this change to a watched build file touch test selection or execution?
 *
 * @param {string} rel repo-relative path
 * @param {string|null} beforeText content at the base (null when added)
 * @param {string|null} afterText content in the working tree (null when deleted)
 * @returns {null | {reason: string, lines: Array<{side: '-'|'+', line: number, code: string, why: string}>}}
 */
export function buildConfigChange(rel, beforeText, afterText) {
  const kind = buildConfigKind(rel);
  if (!kind) return null;
  const a = (beforeText ?? '').replace(/\r\n?/g, '\n');
  const b = (afterText ?? '').replace(/\r\n?/g, '\n');
  if (a === b) return null;
  if (kind === 'test-runner') return { reason: 'test runner configuration changed', lines: [] };
  if (kind === 'package') {
    let pa;
    let pb;
    try {
      const productModule = rel.replaceAll('\\', '/').startsWith('modules/');
      // An added or deleted package.json compares against one without test scripts.
      pa = packageTestProjection(beforeText === null ? '{}' : a, { productModule });
      pb = packageTestProjection(afterText === null ? '{}' : b, { productModule });
    } catch {
      return { reason: 'package.json could not be parsed; flagged on doubt', lines: [] };
    }
    if (pa === pb) return null;
    return { reason: 'package.json test scripts or test runner config changed', lines: [] };
  }
  const aLines = a.split('\n');
  const bLines = b.split('\n');
  const { removed, added } = changedLineIndices(aLines, bLines);
  if (kind === 'properties') {
    const hit = (line) => {
      const code = line.replace(/^\s*[#!].*$/, '');
      return code.trim() && identifierParts(code).some((p) => TEST_PARTS.has(p));
    };
    const lines = [
      ...removed.filter((i) => hit(aLines[i])).map((i) => ({ side: '-', line: i + 1, code: aLines[i].trim(), why: 'names test execution' })),
      ...added.filter((i) => hit(bLines[i])).map((i) => ({ side: '+', line: i + 1, code: bLines[i].trim(), why: 'names test execution' })),
    ];
    return lines.length ? { reason: 'gradle.properties: test execution properties changed', lines } : null;
  }
  const js = kind === 'js-config';
  const sa = scanBlocks(a, { js });
  const sb = scanBlocks(b, { js });
  if ((beforeText !== null && !sa.balanced) || (afterText !== null && !sb.balanced)) {
    return { reason: 'build file could not be scanned (unbalanced braces); flagged on doubt', lines: [] };
  }
  const lines = [
    ...testishLines(sa, removed, { js }).map((l) => ({ side: '-', ...l })),
    ...testishLines(sb, added, { js }).map((l) => ({ side: '+', ...l })),
  ];
  if (lines.length === 0) return null;
  const first = lines[0];
  return {
    reason: `build configuration: changed lines touch test selection or execution (${first.side}${first.line} ${first.why}${lines.length > 1 ? `; ${lines.length} lines` : ''})`,
    lines,
  };
}
