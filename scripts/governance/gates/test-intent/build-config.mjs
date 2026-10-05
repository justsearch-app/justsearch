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
 *   junit-platform  junit-platform.properties anywhere (scripts/ included): every change counts. On a
 *               test runtime classpath it reconfigures every JUnit run of the module, e.g.
 *               `junit.platform.execution.dryRun.enabled=true` skips every test with BUILD SUCCESSFUL.
 *               The same for META-INF/services/org.junit.platform.* anywhere: the ServiceLoader
 *               registrations of launcher listeners and post-discovery filters, which can drop or
 *               skip tests the same way. One inside a JVM test source set is already test code
 *               (scope.mjs).
 *   settings    settings.gradle(.kts): the gradle rules, plus a project an `include` statement names
 *               at the base and no `include` names at the head (its tests leave the build). An
 *               include line whose arguments are not plain string literals counts on doubt.
 *   workflow    .github/workflows/*.y(a)ml: a changed line counts when (a) it runs or selects
 *               product tests itself (a Gradle invocation naming test, check or build tasks, test
 *               properties or a task list expression; a colon-qualified Gradle test task such as a
 *               lane task list entry; a `-x` / `--exclude-task` exclusion; vitest, jest, mocha,
 *               `playwright test`, an npm/pnpm/yarn test script, `cargo test`, `node --test` outside
 *               scripts/; a root package script only when the root package.json command reaches the
 *               product suite), (b) it lies in a step that runs tests ((a) on any of its lines, or it
 *               reads a matrix/env value holding test tasks), (c) it is a job-level `if`,
 *               `continue-on-error`, `runs-on` or `env` line of a job that runs tests, or a matrix
 *               line of one under `exclude` or whose key a test step or such a line reads, or (d) it
 *               is a trigger (`on:`) line of a workflow that runs tests. `name`, `id`, `key`,
 *               `restore-keys` and `description` lines and comments never count (step names and cache
 *               keys do not select tests).
 *
 * On doubt the file is flagged: a file whose braces do not balance, or a package.json that does not
 * parse, counts as changed. Otherwise scripts/** is governance tooling and out of scope
 * (OUT_OF_SCOPE_STATEMENT).
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
  if (p.split('/').includes('node_modules')) return null;
  const base = p.slice(p.lastIndexOf('/') + 1);
  // JUnit Platform reads this file from the test runtime classpath wherever it sits.
  if (base === 'junit-platform.properties') return 'junit-platform';
  // ServiceLoader registrations JUnit Platform loads (launcher listeners, post-discovery filters).
  if (/(?:^|\/)META-INF\/services\/org\.junit\.platform\.[^/]+$/.test(p)) return 'junit-platform';
  if (p.startsWith('scripts/')) return null;
  if (/^\.github\/workflows\/[^/]+\.ya?ml$/.test(p)) return 'workflow';
  if (/^settings\.gradle(\.kts)?$/.test(base)) return 'settings';
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

// ---- settings.gradle(.kts): projects leaving the build ----------------------------------------------

/** First line of the include statement a line belongs to, or -1. */
function includeStatementOf(scan, idx) {
  let i = idx;
  while (i > 0) {
    const l = scan.lines[i];
    const prev = scan.lines[i - 1].code.trim();
    if (l.parenAtStart > 0 || !l.code.trim() || prev.endsWith(',')) i--;
    else break;
  }
  return /(?:^|[^\w.$])include\s*(?:\(|["'])/.test(scan.lines[i].code) ? i : -1;
}

const STRING_LITERAL = /"((?:[^"\\\n]|\\.)*)"|'((?:[^'\\\n]|\\.)*)'/g;

/** The string literals of one code line. */
const literalsOf = (code) => [...code.matchAll(STRING_LITERAL)].map((m) => m[1] ?? m[2]);

/** Every project a static `include` statement of the scanned settings names. */
function includedProjects(scan) {
  const out = new Set();
  scan.lines.forEach((l, i) => {
    if (l.code.trim() && includeStatementOf(scan, i) >= 0) for (const s of literalsOf(l.code)) out.add(s);
  });
  return out;
}

/** Removed lines of a settings file that take a project out of the build. */
function includeRemovals(sa, sb, removed) {
  const head = includedProjects(sb);
  const out = [];
  for (const idx of removed) {
    const code = sa.lines[idx]?.code ?? '';
    if (!code.trim() || includeStatementOf(sa, idx) < 0) continue;
    const gone = literalsOf(code).filter((s) => !head.has(s));
    const residue = code.replace(STRING_LITERAL, '').replace(/^\s*include\b/, '').replace(/[\s(),]/g, '');
    if (gone.length > 0) {
      out.push({ idx, why: `removes ${gone.join(', ')} from the build (no include names it at the head)` });
    } else if (residue || literalsOf(code).some((s) => s.includes('$'))) {
      out.push({ idx, why: 'changes an include whose arguments are not plain string literals; flagged on doubt' });
    }
  }
  return out.map((r) => ({ line: r.idx + 1, code: sa.lines[r.idx].code.trim(), why: r.why }));
}

// ---- .github/workflows: lines that run or select tests -------------------------------------------

/** Identifier parts that name tests; `timeout` is left out (`timeout-minutes` is on every job). */
const WORKFLOW_TEST_PARTS = new Set([...TEST_PARTS].filter((p) => !p.startsWith('timeout')));

const GRADLE_INVOCATION = /gradlew|(?:^|[\s;&|(])gradle\s+[-:A-Za-z]/;
/** A colon-qualified Gradle test task (`:modules:core:test`, `:modules:x:integrationTest`). */
const GRADLE_TEST_TASK_PATH = /(?:^|[^\w:.-])(?::[\w.-]+)*:(?:test|check|[A-Za-z]*Tests?)(?![\w.-])/;
/** Gradle run with a task list from an expression or variable (`./gradlew ${{ matrix.tasks }}`). */
const GRADLE_TASK_EXPRESSION = /(?:gradlew(?:\.bat)?|\bgradle)["']?\s+[^;&|]*\$(?:\{\{|\{|\w)/;
/** A bare task on a Gradle command line that runs tests. */
const GRADLE_BARE_TEST_TASK = /(?:^|\s)["']?(?:test|check|build|[A-Za-z]*Tests?)["']?(?=\s|$|[;&|)\\])/;
/** A task exclusion (`-x :modules:core:test`, `--exclude-task test`). */
const TASK_EXCLUSION = /(?:^|\s)(?:-x\s+["']?:?[A-Za-z$][\w:.${}-]*["']?(?=\s|$|[;&|)\\])|--exclude-task\b)/;
const FRONTEND_RUNNER = /\bvitest\b|\bjest\b|\bmocha\b|\bplaywright\s+test\b/;
/** An npm/pnpm/yarn test script run: [1] what precedes the script, [2] a `test*` script name. */
const PACKAGE_TEST_SCRIPT = /\b(?:npm|pnpm|yarn)\b(.*?)\s(?:test|t|run(?:-script)?\s+(test[\w:.-]*))(?=\s|$|["';&|)])/;
/** Options that run a package script somewhere other than the repository root. */
const PACKAGE_ELSEWHERE = /(?:^|\s)(?:--prefix|-C|--dir|--cwd|-w|--workspace|--filter)(?:\s|=)/;
const CARGO_TEST = /\bcargo\s+(?:test|nextest)\b/;
const NODE_TEST = /\bnode\b.*\s--test\b/;

/**
 * Does one workflow code line run or select product tests by itself?
 *
 * @param {{rootScriptRunsProductTests?: (name: string) => boolean}} [options] resolves a test script
 *   run at the repository root; without it every test script run counts.
 */
export function workflowLineRunsTests(code, { rootScriptRunsProductTests } = {}) {
  if (GRADLE_TEST_TASK_PATH.test(code) || TASK_EXCLUSION.test(code)) return true;
  if (GRADLE_INVOCATION.test(code)) {
    if (GRADLE_BARE_TEST_TASK.test(code) || /\s--tests\b/.test(code) || GRADLE_TASK_EXPRESSION.test(code)) return true;
    if (identifierParts(code).some((p) => WORKFLOW_TEST_PARTS.has(p))) return true;
  }
  if (FRONTEND_RUNNER.test(code) || CARGO_TEST.test(code)) return true;
  const pkg = PACKAGE_TEST_SCRIPT.exec(code);
  if (pkg) {
    // A root script counts as the package.json rule counts it: when its command reaches the product
    // suite. A script run elsewhere, an unknown one, or no resolver: counted on doubt.
    const atRoot = rootScriptRunsProductTests && !PACKAGE_ELSEWHERE.test(pkg[1]) && !/\bcd\s/.test(code.slice(0, pkg.index));
    if (!atRoot || rootScriptRunsProductTests(pkg[2] ?? 'test')) return true;
  }
  // `node --test scripts/...` runs governance self-tests, which are out of scope (D1).
  return NODE_TEST.test(code) && !/(?:^|[\s"'])(?:\.\/)?scripts\//.test(code);
}

/** Keys whose lines never select tests. */
const WORKFLOW_NEUTRAL_KEYS = new Set(['name', 'id', 'key', 'restore-keys', 'description']);
/** Job-level keys of a test job that decide whether, where or how strictly it runs. */
const WORKFLOW_JOB_CONTROL_KEYS = new Set(['if', 'continue-on-error', 'runs-on', 'env', 'strategy']);

const escapeRegExp = (t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Resolver for test scripts run at the repository root, from the root package.json at the base and at
 * the head: a script counts when either side's command reaches the product suite, runs another
 * package script, or is missing. Undefined (count every run) when a side does not parse.
 */
export function rootScriptResolver(texts) {
  if (!texts) return undefined;
  const defs = [];
  for (const t of texts) {
    if (t === null || t === undefined) continue;
    try {
      const scripts = JSON.parse(t)?.scripts;
      if (scripts && typeof scripts === 'object') defs.push(scripts);
    } catch {
      return undefined;
    }
  }
  return (name) => {
    const cmds = defs.map((s) => s[name]).filter((c) => typeof c === 'string');
    return cmds.length === 0 || cmds.some((c) => PRODUCT_TEST_COMMAND.test(c) || /\b(?:npm|pnpm|yarn)\b/.test(c));
  };
}

/** Strip a YAML (or shell) comment: `#` at the start or after whitespace, outside quotes. */
function stripHashComment(line) {
  let quote = '';
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (quote) {
      if (ch === quote) quote = '';
    } else if (ch === '"' || ch === "'") {
      quote = ch;
    } else if (ch === '#' && (i === 0 || /\s/.test(line[i - 1]))) {
      return line.slice(0, i).trimEnd();
    }
  }
  return line.trimEnd();
}

/**
 * Per-line structure of a workflow, from indentation: the top-level key, the job, the job-level key,
 * the step (by the line its list item starts on) and the innermost key. Block scalars (`run: |`)
 * belong to their key; comment lines have no code.
 *
 * @returns {{lines: Array<{code: string, top?: string, job?: string, jobKey?: string, step?: string,
 *            key?: string, keys: string[]}>}}
 */
export function scanWorkflow(text) {
  const lines = [];
  const stack = [];
  let scalarIndent = null;
  text.split('\n').forEach((raw, lineNo) => {
    const ctx = () => {
      const keys = stack.filter((e) => e.key !== '-');
      const top = stack[0]?.key;
      const out = { top, key: keys.length ? keys[keys.length - 1].key : undefined, keys: keys.map((e) => e.key) };
      if (top === 'jobs') {
        out.job = keys[1]?.key;
        out.jobKey = keys[2]?.key;
        if (out.jobKey === 'steps') {
          const at = stack.indexOf(keys[2]);
          const item = stack.slice(at + 1).find((e) => e.key === '-');
          if (item) out.step = `${out.job}@${item.line}`;
        }
      }
      return out;
    };
    if (!raw.trim()) {
      lines.push({ code: '', ...ctx() });
      return;
    }
    const indent = /^ */.exec(raw)[0].length;
    if (scalarIndent !== null && indent > scalarIndent) {
      lines.push({ code: raw.trim().startsWith('#') ? '' : stripHashComment(raw), ...ctx() });
      return;
    }
    scalarIndent = null;
    if (raw.trim().startsWith('#')) {
      lines.push({ code: '', ...ctx() });
      return;
    }
    const code = stripHashComment(raw);
    while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
    let at = indent;
    let rest = code.slice(indent);
    for (let m = /^-(?:\s+|$)/.exec(rest); m; m = /^-(?:\s+|$)/.exec(rest)) {
      stack.push({ indent: at, key: '-', line: lineNo });
      at += m[0].length;
      rest = rest.slice(m[0].length);
    }
    const km = /^("[^"]*"|'[^']*'|[^\s"'#][^:]*?)\s*:(?:\s|$)/.exec(rest);
    if (km) {
      stack.push({ indent: at, key: km[1].replace(/^["']|["']$/g, ''), line: lineNo });
      if (/^[|>][-+0-9]*$/.test(rest.slice(km[0].length).trim())) scalarIndent = at;
    }
    lines.push({ code, ...ctx() });
  });
  return { lines };
}

/**
 * Changed lines of one side of a workflow that run or select tests, as {line, code, why} (1-based).
 *
 * A test step is a step with a line that runs tests, or one that reads a matrix or env value holding
 * test tasks (`"$GW" ${{ matrix.gradle_tasks }}`); a test job is a job with either. A matrix line of a
 * test job counts when it is under `exclude` or its key is read by a test step or a job-level control
 * line (`runs-on: ${{ matrix.os }}`).
 */
function workflowTestLines(scan, indices, { rootScriptRunsProductTests } = {}) {
  // Package scripts run under a working-directory are not root scripts.
  const elsewhere = new Set();
  for (const l of scan.lines) {
    if (l.key === 'working-directory' && l.code.trim()) elsewhere.add(l.step ?? (l.job ? `job:${l.job}` : '*'));
  }
  const optionsFor = (l) => (elsewhere.has('*') || elsewhere.has(l.step) || elsewhere.has(`job:${l.job}`) ? {} : { rootScriptRunsProductTests });
  const runs = scan.lines.map((l) => !!l.code.trim() && workflowLineRunsTests(l.code, optionsFor(l)));
  const taskValues = [...new Set(scan.lines
    .filter((l, i) => runs[i] && !l.step && l.key && (l.jobKey === 'strategy' || l.jobKey === 'env' || l.top === 'env'))
    .map((l) => escapeRegExp(l.key)))];
  const valueRead = taskValues.length === 0 ? null
    : new RegExp(`(?:(?:matrix|env)\\.|\\$\\{?)(?:${taskValues.join('|')})(?![\\w-])`);
  const testSteps = new Set();
  scan.lines.forEach((l, i) => {
    if (l.step && (runs[i] || valueRead?.test(l.code))) testSteps.add(l.step);
  });
  const testJobs = new Set(scan.lines.filter((l, i) => l.job && (runs[i] || testSteps.has(l.step))).map((l) => l.job));
  const matrixRead = new Set();
  for (const l of scan.lines) {
    const reader = (l.step && testSteps.has(l.step))
      || (!l.step && testJobs.has(l.job) && WORKFLOW_JOB_CONTROL_KEYS.has(l.jobKey) && l.jobKey !== 'strategy');
    if (reader) for (const m of l.code.matchAll(/matrix\.([\w-]+)/g)) matrixRead.add(`${l.job}/${m[1]}`);
  }
  const out = [];
  for (const idx of indices) {
    const l = scan.lines[idx];
    if (!l || !l.code.trim() || WORKFLOW_NEUTRAL_KEYS.has(l.key)) continue;
    let why = null;
    if (runs[idx]) why = 'runs or selects tests';
    else if (l.step && testSteps.has(l.step)) why = 'inside a step that runs tests';
    else if (!l.step && l.job && testJobs.has(l.job) && WORKFLOW_JOB_CONTROL_KEYS.has(l.jobKey)) {
      if (l.jobKey !== 'strategy') why = `job-level '${l.jobKey}' of a job that runs tests`;
      else if (l.keys.includes('exclude')) why = 'matrix exclusion of a job that runs tests';
      else if (matrixRead.has(`${l.job}/${l.key}`)) why = `matrix value '${l.key}' that a test step or the job's control reads`;
    } else if (l.top === 'on' && testJobs.size > 0) why = 'trigger of a workflow that runs tests';
    if (why) out.push({ line: idx + 1, code: l.code.trim(), why });
  }
  return out;
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
 * @param {{rootPackageJson?: Array<string|null>}} [options] the root package.json at the base and at
 *   the head, so a workflow's root test script runs resolve to what they run (governance tooling under
 *   scripts/ does not count); without it every test script run counts
 * @returns {null | {reason: string, lines: Array<{side: '-'|'+', line: number, code: string, why: string}>}}
 */
export function buildConfigChange(rel, beforeText, afterText, options = {}) {
  const kind = buildConfigKind(rel);
  if (!kind) return null;
  const a = (beforeText ?? '').replace(/\r\n?/g, '\n');
  const b = (afterText ?? '').replace(/\r\n?/g, '\n');
  if (a === b) return null;
  if (kind === 'test-runner') return { reason: 'test runner configuration changed', lines: [] };
  if (kind === 'junit-platform') {
    const what = rel.endsWith('junit-platform.properties') ? 'junit-platform.properties' : 'a JUnit Platform service registration';
    return { reason: `JUnit Platform configuration changed (${what})`, lines: [] };
  }
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
  if (kind === 'workflow') {
    const rootScriptRunsProductTests = rootScriptResolver(options.rootPackageJson);
    const lines = [
      ...workflowTestLines(scanWorkflow(a), removed, { rootScriptRunsProductTests }).map((l) => ({ side: '-', ...l })),
      ...workflowTestLines(scanWorkflow(b), added, { rootScriptRunsProductTests }).map((l) => ({ side: '+', ...l })),
    ];
    return lines.length ? { reason: summary('CI workflow', lines), lines } : null;
  }
  const js = kind === 'js-config';
  const sa = scanBlocks(a, { js });
  const sb = scanBlocks(b, { js });
  if ((beforeText !== null && !sa.balanced) || (afterText !== null && !sb.balanced)) {
    return { reason: 'build file could not be scanned (unbalanced braces); flagged on doubt', lines: [] };
  }
  const seen = new Set();
  const lines = [
    ...testishLines(sa, removed, { js }).map((l) => ({ side: '-', ...l })),
    ...(kind === 'settings' ? includeRemovals(sa, sb, removed).map((l) => ({ side: '-', ...l })) : []),
    ...testishLines(sb, added, { js }).map((l) => ({ side: '+', ...l })),
  ].filter((l) => !seen.has(`${l.side}${l.line}`) && seen.add(`${l.side}${l.line}`));
  if (lines.length === 0) return null;
  return { reason: summary('build configuration', lines), lines };
}

function summary(what, lines) {
  const first = lines[0];
  return `${what}: changed lines touch test selection or execution (${first.side}${first.line} ${first.why}${lines.length > 1 ? `; ${lines.length} lines` : ''})`;
}
