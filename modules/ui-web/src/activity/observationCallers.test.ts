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

/** Parse executable syntax, not comments, documentation, or a route inventory. Resolve local
 * endpoint constants, URL builders and imported constants before looking at request arguments.
 * Generic catalog transports name their input and the actual catalog constant in the reason. */
function requestedUrls(file: string, bindings: Map<string, string> = new Map()): string[] {
  const source = ts.createSourceFile(file, readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true,
    file.endsWith('jsx') ? ts.ScriptKind.JSX : ts.ScriptKind.TS);
  const values = new Map<string, ts.Expression[]>();
  const functions = new Map<string, ts.Node>();
  const imports = new Map<string, { file: string; symbol: string }>();
  const sinks: ts.Node[] = [];
  function visit(node: ts.Node): void {
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer) {
      values.set(node.name.text, [...(values.get(node.name.text) ?? []), node.initializer]);
    }
    if ((ts.isFunctionDeclaration(node) || ts.isMethodDeclaration(node)) && node.name && node.body) {
      functions.set(node.name.getText(source), node.body);
    }
    if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) {
      const clause = node.importClause?.namedBindings;
      if (clause && ts.isNamedImports(clause) && node.moduleSpecifier.text.startsWith('.')) {
        const imported = resolve(dirname(file), node.moduleSpecifier.text.replace(/\.js$/, '.ts'));
        for (const element of clause.elements) imports.set(element.name.text,
          { file: imported, symbol: element.propertyName?.text ?? element.name.text });
      }
    }
    if (ts.isCallExpression(node)) {
      const name = node.expression.getText(source).split('.').at(-1)!;
      const alias = values.get(name)?.some((value) => /(?:authorizedFetch|fetchImpl|fetchFn|\bfetch\b)/.test(value.getText(source)));
      if (alias || /^(fetch|authorizedFetch|fetchImpl|fetchFn|request|doFetch|fetchJson|api|subscribePooled|streamViaHost|consumeShapeStream)$/.test(name)
          || ts.isParenthesizedExpression(node.expression) && /(?:authorizedFetch|fetchImpl)/.test(node.expression.getText(source))) {
        sinks.push(...node.arguments);
      }
    }
    if (ts.isNewExpression(node) && /(?:EventSource|EnvelopeStream|MultiplexedStream)$/.test(node.expression.getText(source))) {
      sinks.push(...(node.arguments ?? []));
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
  function evaluate(node: ts.Node, seen = new Set<string>()): string[] {
    const text = node.getText(source);
    if (bindings.has(text)) return [bindings.get(text)!];
    if (ts.isStringLiteralLike(node)) return [node.text];
    if (ts.isTemplateExpression(node)) {
      let paths = [node.head.text];
      for (const span of node.templateSpans) {
        const parts = evaluate(span.expression, seen);
        paths = paths.flatMap((path) => (parts.length ? parts : ['__']).map((part) => path + part + span.literal.text));
      }
      return paths;
    }
    if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) {
      const left = evaluate(node.left, seen), right = evaluate(node.right, seen);
      return (left.length ? left : ['__']).flatMap((a) => (right.length ? right : ['__']).map((b) => a + b));
    }
    if (ts.isIdentifier(node) && !seen.has(text)) {
      const next = new Set(seen).add(text);
      if (imports.has(text)) {
        const imported = imports.get(text)!;
        const importedSource = readFileSync(imported.file, 'utf8');
        const constant = importedSource.match(new RegExp(`(?:const|let)\\s+${imported.symbol}\\s*=\\s*['\"]([^'\"]+)`));
        if (constant) return [constant[1]!];
      }
      return (values.get(text) ?? []).flatMap((value) => evaluate(value, next));
    }
    if (ts.isCallExpression(node)) {
      const name = node.expression.getText(source).split('.').at(-1)!;
      if (name === 'apiPath' && node.arguments[0] && ts.isStringLiteral(node.arguments[0])) {
        const operation = node.arguments[0].text;
        const generated = readFileSync(resolve(root, 'modules/ui-web/src/api/generated/apiRoutes.ts'), 'utf8');
        expect(generated).toContain('"' + operation + '"');
        return [operation.split(' ')[1]!];
      }
      if (name === 'composeEndpointUrl') return node.arguments.flatMap((arg) => evaluate(arg, seen));
      const body = functions.get(name);
      if (body && !seen.has(name)) {
        const returned: string[] = [];
        function returns(n: ts.Node): void {
          if (ts.isReturnStatement(n) && n.expression) returned.push(...evaluate(n.expression, new Set(seen).add(name)));
          ts.forEachChild(n, returns);
        }
        returns(body);
        return returned;
      }
      // URL composition preserves its path argument.
    }
    if (ts.isObjectLiteralExpression(node)) return node.properties.flatMap((property) => {
      if (ts.isShorthandPropertyAssignment(property) && property.name.text === 'url') return evaluate(property.name, seen);
      return ts.isPropertyAssignment(property) && property.name.getText(source) === 'url'
        ? evaluate(property.initializer, seen) : [];
    });
    if (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) || ts.isAwaitExpression(node)) return evaluate(node.expression, seen);
    if (ts.isConditionalExpression(node)) return [...evaluate(node.whenTrue, seen), ...evaluate(node.whenFalse, seen)];
    return [];
  }
  return sinks.flatMap((sink) => evaluate(sink));
}

function checkCaller(operation: string, evidence: string): void {
  const match = evidence.match(/^CONSUMER: (\S+\.(?:ts|jsx|mjs))(?: \[([^=]+)=([^#\]]+)#(\w+)\])? /);
  expect(match, `${operation}: name one requesting source, with an optional catalog binding`).not.toBeNull();
  const file = resolve(root, match![1]!.startsWith('scripts/') ? match![1]! : 'modules/' + match![1]!);
  const bindings = new Map<string, string>();
  // These bootstrap wrappers pass fixed namespaces to the shared fetcher in this file.
  const namespace = operation.match(/^GET \/api\/messages\/([^/]+)\//)?.[1];
  if (namespace && namespace !== 'registry-resource' && match![1]!.endsWith('/resourceCatalog.ts')) {
    const code = readFileSync(file, 'utf8');
    expect(code).toMatch(new RegExp(`bootMessageCatalog\\(baseUrl, ['\"]${namespace}['\"]\\)`));
    bindings.set('namespace', namespace);
  }
  if (match![2]) {
    const catalog = readFileSync(resolve(root, 'modules/' + match![3]!), 'utf8')
      .replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, '');
    const constant = catalog.match(new RegExp(`String\\s+${match![4]}\\s*=\\s*"([^"]+)"`));
    expect(constant, `${operation}: missing catalog endpoint constant`).not.toBeNull();
    expect(constant![1]).toBe(operation.split(' ')[1]);
    bindings.set(match![2]!, constant![1]!);
  }
  const route = operation.split(' ')[1]!;
  const regex = new RegExp('^' + route.split(/\{[^}]+\}/).map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('[^/?]+') + '(?:\\?|$)');
  const requests = requestedUrls(file, bindings).map((url) => url.slice(Math.max(url.indexOf('/api/'), url.indexOf('/infra/'))));
  expect(requests.some((url) => regex.test(url)), `${operation}: ${match![1]} does not request this route; found ${requests.join(', ')}`).toBe(true);
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
    it(operation, () => checkCaller(operation, reason));
  }
  it('rejects a plausible caller that only mentions memory in a comment', () => {
    expect(() => checkCaller('GET /api/memory',
      'CONSUMER: ui-web/src/shell-v0/state/readinessNotice.ts automatic hydration')).toThrow();
    const actual = reasons.find((entry) => entry.operation === 'GET /api/memory')!;
    // Fails on the before version: its actual justification names this wrong caller.
    expect(() => checkCaller(actual.operation, actual.reason)).not.toThrow();
  });
});
