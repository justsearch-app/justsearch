// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

const root = resolve(import.meta.dirname, '../../../..');
const policy = readFileSync(resolve(root,
  'modules/ui/src/main/java/io/justsearch/ui/api/ObservationJustifications.java'), 'utf8');
const table = policy.slice(policy.indexOf('REASONS ='), policy.indexOf('/** Also used'));
const reasons = [...table.matchAll(/Map\.entry\("([^"]+)", "([^"]+)"\)/g)]
  .map((m) => ({ operation: m[1]!, reason: m[2]! }));

type Read = (file: string) => string;
const readUtf8: Read = (file) => readFileSync(file, 'utf8');

/** Evidence grammar: `CONSUMER: <requesting file> [<binding>=<catalog>#<CONST>] (via <file>)* <purpose>`. */
const EVIDENCE = /^CONSUMER: (\S+\.(?:ts|jsx|mjs))(?: \[([^=]+)=([^#\]]+)#(\w+)\])?((?: via \S+\.(?:ts|jsx|mjs))*) /;

/** Transports whose request target and method are read from fixed argument positions. */
const FETCH_LIKE = /^(?:fetch|authorizedFetch|fetchImpl|fetchFn)$/;
const GET_STREAMS = /(?:^|\.)(?:EventSource|EnvelopeStream|MultiplexedStream)$/;

/** Shared request helpers outside the requesting file: [module, export] -> target arguments, init argument. */
const SHARED_WRAPPERS = new Map([['modules/ui-web/src/api/http.ts#request', { target: [0, 1], init: 2 }]]);

/** Stream transports: callbacks handed to them run on stream deliveries. */
const STREAM_TRANSPORTS = /^(?:EventSource|EnvelopeStream|MultiplexedStream|subscribePooled|consumeShapeStream|streamViaHost)$/;

/** Unattended triggers: these run with no person acting. Anything else is not proof. */
const LIFECYCLE = new Set(['constructor', 'connectedCallback', 'firstUpdated', 'render', 'hostConnected']);
const REPEATING = new Set(['setInterval']);
/** Store, catalog and stream subscriptions, and change publishers, deliver state changes as they happen. */
const DELIVERY = /^(?:subscribe\w*|set\w*Publisher)$/;
const STREAM_EVENTS = new Set(['message', 'open', 'error']);
/** A callback here runs only when the code that registered it runs, so keep walking outwards. */
const CONTINUATION = new Set(['then', 'catch', 'finally', 'setTimeout', 'requestAnimationFrame', 'queueMicrotask',
  'forEach', 'map', 'flatMap', 'filter', 'all', 'allSettled', 'race', 'Promise']);

const placeholder = (index: number): string => `\uE000${index}\uE000`;
const HOLE = /\uE000(\d+)\uE000/;

interface Parsed { file: string; source: ts.SourceFile; values: Map<string, ts.VariableDeclaration[]>; functions: Map<string, ts.Node> }
interface Request { site: ts.Node; targets: string[]; methods: string[] }

function parse(file: string, read: Read): Parsed {
  const source = ts.createSourceFile(file, read(file), ts.ScriptTarget.Latest, true,
    file.endsWith('jsx') ? ts.ScriptKind.JSX : ts.ScriptKind.TS);
  const values = new Map<string, ts.VariableDeclaration[]>();
  const functions = new Map<string, ts.Node>();
  (function visit(node: ts.Node): void {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer) {
      values.set(node.name.text, [...(values.get(node.name.text) ?? []), node]);
    }
    if ((ts.isFunctionDeclaration(node) || ts.isMethodDeclaration(node)) && node.name && node.body) {
      functions.set(node.name.getText(source), node.body);
    }
    ts.forEachChild(node, visit);
  })(source);
  return { file, source, values, functions };
}

/** Initializers of the variables named `name` that are lexically visible from `from`. */
function visible(p: Parsed, name: string, from: ts.Node): ts.Expression[] {
  return (p.values.get(name) ?? []).filter((declaration) => {
    const owner = enclosingFunction(declaration);
    for (let at: ts.Node | undefined = from; at; at = at.parent) if (at === owner) return true;
    return !owner;
  }).map((declaration) => declaration.initializer!);
}

/** Whether a relative module specifier written in `from` names `file` (extensions are optional). */
const names = (from: string, specifier: string, file: string): boolean => specifier.startsWith('.')
  && resolve(dirname(from), specifier).replace(/\.(?:js|ts|jsx|mjs)$/, '') === file.replace(/\.(?:js|ts|jsx|mjs)$/, '');

const unwrap = (node: ts.Node): ts.Node =>
  ts.isParenthesizedExpression(node.parent) || ts.isAsExpression(node.parent) ? unwrap(node.parent) : node;
const calleeName = (call: ts.CallExpression | ts.NewExpression): string =>
  call.expression.getText().replace(/<[^>]*>$/, '').split('.').at(-1)!;
function enclosingFunction(node: ts.Node): ts.SignatureDeclaration | undefined {
  for (let at = node.parent; at; at = at.parent) if (ts.isFunctionLike(at)) return at;
  return undefined;
}
/** The name a function is invoked by: declarations, methods, and functions stored in a variable or field. */
function unitName(fn: ts.SignatureDeclaration): string | undefined {
  if (ts.isConstructorDeclaration(fn)) return 'constructor';
  if ((ts.isFunctionDeclaration(fn) || ts.isMethodDeclaration(fn)) && fn.name) return fn.name.getText();
  const holder = unwrap(fn).parent;
  if ((ts.isVariableDeclaration(holder) || ts.isPropertyDeclaration(holder) || ts.isPropertyAssignment(holder))
    && holder.initializer === unwrap(fn)) {
    return holder.name.getText();
  }
  return undefined;
}

/** Static values of a request argument. Parameters of `scope` become positional placeholders. */
function evaluate(p: Parsed, node: ts.Node, bindings: Map<string, string>, read: Read,
  scope: ts.Node | undefined, seen = new Set<string>()): string[] {
  const text = node.getText(p.source);
  if (bindings.has(text)) return [bindings.get(text)!];
  if (ts.isStringLiteralLike(node)) return [node.text];
  if (ts.isTemplateExpression(node)) {
    let paths = [node.head.text];
    for (const span of node.templateSpans) {
      const parts = evaluate(p, span.expression, bindings, read, scope, seen);
      paths = paths.flatMap((path) => (parts.length ? parts : ['__']).map((part) => path + part + span.literal.text));
    }
    return paths;
  }
  if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    const left = evaluate(p, node.left, bindings, read, scope, seen), right = evaluate(p, node.right, bindings, read, scope, seen);
    return (left.length ? left : ['__']).flatMap((a) => (right.length ? right : ['__']).map((b) => a + b));
  }
  if (ts.isIdentifier(node) && !seen.has(text)) {
    const owner = enclosingFunction(node);
    for (let fn = owner; fn; fn = enclosingFunction(fn)) {
      const index = fn.parameters.findIndex((param) => param.name.getText(p.source) === text);
      if (index >= 0) return fn === scope ? [placeholder(index)] : [];
    }
    const next = new Set(seen).add(text);
    for (const statement of p.source.statements) {
      const clause = ts.isImportDeclaration(statement) && ts.isStringLiteral(statement.moduleSpecifier)
        && statement.moduleSpecifier.text.startsWith('.') ? statement.importClause?.namedBindings : undefined;
      const element = clause && ts.isNamedImports(clause) ? clause.elements.find((e) => e.name.text === text) : undefined;
      if (!element) continue;
      const imported = resolve(dirname(p.file), (statement as ts.ImportDeclaration).moduleSpecifier.getText(p.source)
        .slice(1, -1).replace(/\.js$/, '.ts'));
      const symbol = element.propertyName?.text ?? element.name.text;
      const constant = read(imported).match(new RegExp(`(?:const|let)\\s+${symbol}\\s*=\\s*['"]([^'"]+)`));
      if (constant) return [constant[1]!];
    }
    return visible(p, text, node).flatMap((value) => evaluate(p, value, bindings, read, scope, next));
  }
  if (ts.isCallExpression(node)) {
    const name = calleeName(node);
    if (name === 'apiPath' && node.arguments[0] && ts.isStringLiteral(node.arguments[0])) {
      const operation = node.arguments[0].text;
      expect(read(resolve(root, 'modules/ui-web/src/api/generated/apiRoutes.ts'))).toContain('"' + operation + '"');
      return [operation.split(' ')[1]!];
    }
    if (name === 'composeEndpointUrl') {
      return node.arguments.flatMap((arg) => evaluate(p, arg, bindings, read, scope, seen));
    }
    const body = p.functions.get(name);
    if (body && !seen.has(name)) {
      const returned: string[] = [];
      (function returns(n: ts.Node): void {
        if (ts.isReturnStatement(n) && n.expression) {
          returned.push(...evaluate(p, n.expression, bindings, read, scope, new Set(seen).add(name)));
        }
        ts.forEachChild(n, returns);
      })(body);
      return returned;
    }
  }
  if (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isAwaitExpression(node)) {
    return evaluate(p, node.expression, bindings, read, scope, seen);
  }
  if (ts.isConditionalExpression(node)) {
    return [...evaluate(p, node.whenTrue, bindings, read, scope, seen), ...evaluate(p, node.whenFalse, bindings, read, scope, seen)];
  }
  return [];
}

/** The HTTP method a fetch init selects: absent means GET; an unreadable method matches nothing. */
function methodOf(p: Parsed, init: ts.Expression | undefined, read: Read, scope: ts.Node | undefined): string[] {
  if (!init) return ['GET'];
  const node = ts.isParenthesizedExpression(init) || ts.isAsExpression(init) ? init.expression : init;
  if (ts.isObjectLiteralExpression(node)) {
    const explicit = node.properties.find((property) => ts.isPropertyAssignment(property) && property.name.getText() === 'method');
    if (explicit && ts.isPropertyAssignment(explicit)) {
      // `method: init?.method` forwards the caller's init.
      if (ts.isPropertyAccessExpression(explicit.initializer) && explicit.initializer.name.text === 'method') {
        return methodOf(p, explicit.initializer.expression, read, scope);
      }
      return evaluate(p, explicit.initializer, new Map(), read, scope).map((m) => m.toUpperCase());
    }
    const spreads = node.properties.filter(ts.isSpreadAssignment);
    return spreads.length ? spreads.flatMap((spread) => methodOf(p, spread.expression, read, scope)) : ['GET'];
  }
  if (ts.isIdentifier(node)) {
    const values = evaluate(p, node, new Map(), read, scope);
    if (values.some((value) => HOLE.test(value))) return values;
    return visible(p, node.text, node).flatMap((value) => methodOf(p, value, read, scope));
  }
  return [];
}

/** The request's actual target argument, never an options payload of another request. */
function target(call: ts.CallExpression | ts.NewExpression): ts.Expression | undefined {
  const first = call.arguments?.[0];
  if (!first || !ts.isNewExpression(call)) return first;
  if (!ts.isObjectLiteralExpression(first)) return first;
  const url = first.properties.find((property) => property.name?.getText() === 'url');
  if (url && ts.isShorthandPropertyAssignment(url)) return url.name;
  return url && ts.isPropertyAssignment(url) ? url.initializer : undefined;
}

function sharedWrapper(p: Parsed, local: string): { target: number[]; init: number } | undefined {
  for (const statement of p.source.statements) {
    if (!ts.isImportDeclaration(statement) || !ts.isStringLiteral(statement.moduleSpecifier)) continue;
    const clause = statement.importClause?.namedBindings;
    const element = clause && ts.isNamedImports(clause) ? clause.elements.find((e) => e.name.text === local) : undefined;
    if (!element) continue;
    const module = resolve(dirname(p.file), statement.moduleSpecifier.text.replace(/\.js$/, '') + '.ts');
    return SHARED_WRAPPERS.get(`${module.slice(root.length + 1).replace(/\\/g, '/')}#${element.propertyName?.text ?? local}`);
  }
  return undefined;
}

/** Every request in one file with its static targets and methods. A local function that forwards a
 * parameter into a request is a wrapper: each call to it becomes a request with its own arguments. */
function requestsIn(p: Parsed, bindings: Map<string, string>, read: Read): Request[] {
  const direct: Request[] = [];
  (function visit(node: ts.Node): void {
    if (ts.isCallExpression(node) || ts.isNewExpression(node)) {
      const name = calleeName(node);
      const alias = visible(p, name, node).some((value) => /(?:authorizedFetch|fetchImpl|fetchFn|\bfetch\b)/.test(value.getText(p.source)));
      const postStream = ts.isCallExpression(node) && name === 'consumeShapeStream';
      const fetchLike = ts.isCallExpression(node) && (FETCH_LIKE.test(name) || alias
        || (ts.isParenthesizedExpression(node.expression) && /(?:authorizedFetch|fetchImpl)/.test(node.expression.getText(p.source))));
      const stream = (ts.isNewExpression(node) && GET_STREAMS.test(node.expression.getText(p.source))) || name === 'subscribePooled';
      const fn = enclosingFunction(node);
      const scope = fn && unitName(fn) ? fn : undefined;
      const shared = ts.isCallExpression(node) && ts.isIdentifier(node.expression) ? sharedWrapper(p, node.expression.text) : undefined;
      if (shared && ts.isCallExpression(node)) {
        const parts = shared.target.map((index) => node.arguments[index] ? evaluate(p, node.arguments[index]!, bindings, read, scope) : []);
        direct.push({ site: node, targets: parts.reduce((joined, part) => joined.flatMap((a) =>
          (part.length ? part : ['__']).map((b) => a + b)), ['']), methods: methodOf(p, node.arguments[shared.init], read, scope) });
      }
      const url = fetchLike || stream || postStream ? target(node) : undefined;
      if (url) {
        direct.push({ site: node, targets: evaluate(p, url, bindings, read, scope),
          methods: fetchLike ? methodOf(p, node.arguments?.[1], read, scope) : postStream ? ['POST'] : ['GET'] });
      }
    }
    ts.forEachChild(node, visit);
  })(p.source);
  const expanded: Request[] = [];
  const expand = (request: Request, depth: number): void => {
    const fn = enclosingFunction(request.site);
    const name = fn && unitName(fn);
    if (![...request.targets, ...request.methods].some((value) => HOLE.test(value))) { expanded.push(request); return; }
    // Unfilled parameters (a base URL, a caller's init) stay unknown; the request is still checked here.
    expanded.push({ site: request.site, targets: request.targets.map((t) => t.replace(new RegExp(HOLE.source, 'g'), '__')),
      methods: request.methods.filter((m) => !HOLE.test(m)) });
    if (!fn || !name || depth > 3) return;
    (function calls(node: ts.Node): void {
      if (ts.isCallExpression(node) && calleeName(node) === name && enclosingFunction(node) !== fn) {
        const outer = enclosingFunction(node);
        const scope = outer && unitName(outer) ? outer : undefined;
        // Fill this wrapper's holes once; holes in the filled text belong to the outer function.
        const fill = (value: string, asMethod: boolean): string[] => value.split(/(\uE000\d+\uE000)/)
          .reduce<string[]>((joined, piece) => {
            const hole = piece.match(HOLE);
            const arg = hole ? node.arguments[Number(hole[1])] : undefined;
            const parts = !hole ? [piece] : asMethod ? methodOf(p, arg, read, scope)
              : arg ? evaluate(p, arg, bindings, read, scope) : [];
            return joined.flatMap((a) => parts.map((b) => a + b));
          }, ['']);
        expand({ site: node, targets: request.targets.flatMap((t) => fill(t, false)),
          methods: request.methods.flatMap((m) => fill(m, true)) }, depth + 1);
      }
      ts.forEachChild(node, calls);
    })(p.source);
  };
  for (const request of direct) expand(request, 0);
  return expanded;
}

/** Proof that `site` runs with no person acting: a path to a timer, mount/connection callback,
 * subscription or stream delivery, or module evaluation. Event handlers a person fires, template
 * event bindings, and anything unrecognised are not proof; `via` files continue an exported chain. */
function prove(p: Parsed, site: ts.Node, hops: Parsed[], seen: Set<ts.Node>): string | undefined {
  const fn = enclosingFunction(site);
  if (!fn) return `module evaluation of ${p.file}`;
  if (seen.has(fn)) return undefined;
  seen.add(fn);
  const name = unitName(fn);
  if (name && LIFECYCLE.has(name) && (ts.isClassElement(fn) || ts.isClassElement(unwrap(fn).parent))) return `${name}`;
  if (ts.isFunctionDeclaration(fn) || ts.isMethodDeclaration(fn)) return name ? byName(p, name, hops, seen) : undefined;
  return asValue(p, unwrap(fn), hops, seen) ?? (name ? byName(p, name, hops, seen) : undefined);
}

/** How a function value is invoked, judged only by where it is passed. */
function asValue(p: Parsed, value: ts.Node, hops: Parsed[], seen: Set<ts.Node>): string | undefined {
  const parent = value.parent;
  if (ts.isCallExpression(parent) && parent.expression === value) return prove(p, parent, hops, seen);
  if ((ts.isCallExpression(parent) || ts.isNewExpression(parent)) && parent.arguments?.includes(value as ts.Expression)) {
    const callee = calleeName(parent);
    if (REPEATING.has(callee)) return `${callee} timer`;
    if (DELIVERY.test(callee) || STREAM_TRANSPORTS.test(callee)) return `${callee} delivery`;
    if (callee === 'addEventListener' && parent.arguments[0] && ts.isStringLiteralLike(parent.arguments[0])
      && STREAM_EVENTS.has(parent.arguments[0].text)) return `stream ${parent.arguments[0].text} delivery`;
    if (CONTINUATION.has(callee)) return prove(p, parent, hops, seen);
    return undefined;
  }
  // Stream configuration callbacks: `new EnvelopeStream({ onMessage })`, `subscribePooled(url, l, () => ({ ... }))`.
  if (ts.isPropertyAssignment(parent) && /^on[A-Z]\w*$/.test(parent.name.getText())) {
    let config: ts.Node = unwrap(parent.parent).parent;
    if (ts.isArrowFunction(config)) config = unwrap(config).parent;
    if ((ts.isNewExpression(config) || ts.isCallExpression(config)) && STREAM_TRANSPORTS.test(calleeName(config))) {
      return `stream ${parent.name.getText()} delivery`;
    }
  }
  // Dispatch table: `{ name: handler }` returned by `table()` and invoked as `this.table()[event](...)`.
  if (ts.isPropertyAssignment(parent) && ts.isReturnStatement(unwrap(parent.parent).parent)) {
    const owner = enclosingFunction(parent.parent);
    const table = owner && unitName(owner);
    let proof: string | undefined;
    (function visit(node: ts.Node): void {
      if (proof) return;
      if (table && ts.isCallExpression(node) && ts.isElementAccessExpression(node.expression)
        && ts.isCallExpression(node.expression.expression) && calleeName(node.expression.expression) === table) {
        proof = prove(p, node, hops, seen);
      }
      ts.forEachChild(node, visit);
    })(p.source);
    if (proof) return `${table}()[event] dispatch <- ${proof}`;
  }
  if (ts.isBinaryExpression(parent) && parent.right === value && /\.on(?:message|open|error)$/.test(parent.left.getText())) {
    return `stream ${parent.left.getText().split('.').at(-1)} delivery`;
  }
  return undefined;
}

/** References in this file, then in the next `via` file that imports this one. */
function byName(p: Parsed, name: string, hops: Parsed[], seen: Set<ts.Node>): string | undefined {
  const local = proveReferences(p, name, hops, seen);
  if (local || !hops.length) return local;
  const [next, ...rest] = hops;
  let imports = false;
  (function visit(node: ts.Node): void {
    const specifier = ts.isImportDeclaration(node) ? node.moduleSpecifier
      : ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword ? node.arguments[0] : undefined;
    if (specifier && ts.isStringLiteral(specifier) && names(next!.file, specifier.text, p.file)) imports = true;
    ts.forEachChild(node, visit);
  })(next!.source);
  expect(imports, `${next!.file} must import ${p.file} to continue its call chain`).toBe(true);
  const remote = proveReferences(next!, name, rest, seen, true);
  return remote && `${name} <- ${next!.file.slice(root.length + 1)}: ${remote}`;
}

/** In a `via` file, `this.name` is that file's own member, never the previous file's export or method. */
function proveReferences(p: Parsed, name: string, hops: Parsed[], seen: Set<ts.Node>, remote = false): string | undefined {
  let proof: string | undefined;
  (function visit(node: ts.Node): void {
    if (proof) return;
    const parent = node.parent;
    const declares = parent && (ts.isFunctionDeclaration(parent) || ts.isMethodDeclaration(parent)
      || ts.isVariableDeclaration(parent) || ts.isPropertyDeclaration(parent) || ts.isParameter(parent)
      || ts.isPropertyAssignment(parent) || ts.isImportSpecifier(parent) || ts.isBindingElement(parent))
      && (parent as ts.NamedDeclaration).name === node;
    const own = remote && parent !== undefined && ts.isPropertyAccessExpression(parent) && parent.name === node
      && parent.expression.kind === ts.SyntaxKind.ThisKeyword;
    const reference = ts.isIdentifier(node) && node.text === name && !declares && !own;
    if (reference) {
      const expression = ts.isPropertyAccessExpression(node.parent) && node.parent.name === node ? node.parent : node;
      const value = unwrap(expression);
      const found = ts.isCallExpression(value.parent) && value.parent.expression === value
        ? prove(p, value.parent, hops, seen) : asValue(p, value, hops, seen);
      if (found) proof = `${name} <- ${found}`;
    }
    ts.forEachChild(node, visit);
  })(p.source);
  return proof;
}

function checkCaller(operation: string, evidence: string, read: Read = readUtf8): string {
  const match = evidence.match(EVIDENCE);
  expect(match, `${operation}: name one requesting source, an optional catalog binding, and optional via files`).not.toBeNull();
  const located = (relative: string): string => resolve(root, relative.startsWith('scripts/') ? relative : 'modules/' + relative);
  const file = located(match![1]!);
  const bindings = new Map<string, string>();
  // These bootstrap wrappers pass fixed namespaces to the shared fetcher in this file.
  const namespace = operation.match(/^GET \/api\/messages\/([^/]+)\//)?.[1];
  if (namespace && namespace !== 'registry-resource' && match![1]!.endsWith('/resourceCatalog.ts')) {
    expect(read(file)).toMatch(new RegExp(`bootMessageCatalog\\(baseUrl, ['"]${namespace}['"]\\)`));
    bindings.set('namespace', namespace);
  }
  if (match![2]) {
    const catalog = read(resolve(root, 'modules/' + match![3]!)).replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, '');
    const constant = catalog.match(new RegExp(`String\\s+${match![4]}\\s*=\\s*"([^"]+)"`));
    expect(constant, `${operation}: missing catalog endpoint constant`).not.toBeNull();
    expect(constant![1]).toBe(operation.split(' ')[1]);
    bindings.set(match![2]!, constant![1]!);
  }
  const [method, route] = operation.split(' ') as [string, string];
  const regex = new RegExp('^' + route.split(/\{[^}]+\}/).map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('[^/?]+') + '(?:\\?|$)');
  const parsed = parse(file, read);
  const hops = [...match![5]!.matchAll(/ via (\S+)/g)].map((hop) => parse(located(hop[1]!), read));
  const requests = requestsIn(parsed, bindings, read);
  const matching = requests.filter((request) => request.methods.includes(method)
    && request.targets.some((url) => regex.test(url.slice(Math.max(url.indexOf('/api/'), url.indexOf('/infra/'))))));
  expect(matching.length, `${operation}: ${match![1]} makes no ${method} request to this route; found `
    + requests.map((r) => `${r.methods.join('|')} ${r.targets.join('|')}`).join(', ')).toBeGreaterThan(0);
  const proofs = matching.map((request) => prove(parsed, request.site, hops, new Set())).filter(Boolean);
  expect(proofs, `${operation}: no ${method} request in ${match![1]} is reached from an unattended trigger`).not.toEqual([]);
  return proofs[0]!;
}

describe('observation callers are executable evidence', () => {
  it('checks every table entry and never leaves an unchecked named caller in a purpose reason', () => {
    expect(reasons.length).toBeGreaterThan(0);
    expect(reasons.length).toBe([...table.matchAll(/Map\.entry\(/g)].length);
    for (const { reason } of reasons.filter((entry) => !entry.reason.startsWith('CONSUMER:'))) {
      // Status/health/readiness are justified by the read itself. Named callers belong in
      // CONSUMER evidence, where the executable requester check below must verify them.
      expect(reason).toMatch(/^(STATUS|HEALTH|READINESS): /);
      expect(reason).not.toMatch(/\.(?:ts|jsx|mjs|cjs|py|html)\b/);
    }
  });
  for (const { operation, reason } of reasons.filter((entry) => entry.reason.startsWith('CONSUMER:'))) {
    it(operation, () => { expect(checkCaller(operation, reason)).toBeTruthy(); });
  }
  it('rejects a plausible caller that only mentions memory in a comment', () => {
    expect(() => checkCaller('GET /api/memory',
      'CONSUMER: ui-web/src/shell-v0/state/readinessNotice.ts automatic hydration')).toThrow();
    const actual = reasons.find((entry) => entry.operation === 'GET /api/memory')!;
    // Fails on the before version: its actual justification names this wrong caller.
    expect(() => checkCaller(actual.operation, actual.reason)).not.toThrow();
  });
});

// Independent verifier adversaries (verify-vdu6 mechanical.test.ts), unchanged fixtures and assertions,
// run through this file's checker with a virtual source.
describe('independent mechanical adversaries', () => {
  let fixture = '';
  const read: Read = (file) => (file.endsWith('hypothetical.ts') ? fixture : readUtf8(file));
  const evidence = 'CONSUMER: ui-web/src/hypothetical.ts automatic hydration';
  it('accepts actual unattended GET hydration', () => {
    fixture = "setInterval(() => fetch('/api/memory'),5000);";
    expect(() => checkCaller('GET /api/memory', evidence, read)).not.toThrow();
  });
  it('rejects route text with no executable request', () => {
    fixture = "// fetch('/api/memory')";
    expect(() => checkCaller('GET /api/memory', evidence, read)).toThrow();
  });
  it('rejects an exclusively attended click handler', () => {
    fixture = "button.addEventListener('click', () => authorizedFetch('/api/memory'));";
    expect(() => checkCaller('GET /api/memory', evidence, read)).toThrow();
  });
  it('does not confuse POST with GET', () => {
    fixture = "setInterval(() => authorizedFetch('/api/memory',{method:'POST'}),5000);";
    expect(() => checkCaller('GET /api/memory', evidence, read)).toThrow();
  });
  it('does not confuse a transport payload with its URL', () => {
    fixture = "setInterval(() => {const options={method:'POST',url:'/api/memory'}; return fetch('/api/different',options);},5000);";
    expect(() => checkCaller('GET /api/memory', evidence, read)).toThrow();
  });
});
