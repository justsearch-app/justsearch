// Reproduce: node gates/test-intent/evidence/token-bootstrap-adaptation-audit.mjs
// Static assertion inventory, not D4 execution evidence. Full diffs retain setup/tags for review.
import fs from 'node:fs';
import cp from 'node:child_process';
import crypto from 'node:crypto';
import ts from '../../../modules/ui-web/node_modules/typescript/lib/typescript.js';
const baseline = 'fe3753d37e585f35c61ae5f1488a909739d4b47c';
const web = [
  'src/activity/idleSurfaces.test.ts',
  'src/shell-v0/components/ResourceView.history-reconnect.test.ts',
  'src/shell-v0/components/ResourceView.render.test.ts',
  'src/shell-v0/components/advisory/AdvisoryStore.test.ts',
  'src/shell-v0/controllers/AgentSessionStreamOwnership.test.ts',
  'src/shell-v0/substrates/tasks/indexingJobsBridge.test.ts',
  'src/shell-v0/utils/aiInstallPoll.test.ts',
  'src/shell-v0/views/HealthLitView.test.ts',
].map(p => `modules/ui-web/${p}`);
const java = [
  'modules/ui/src/integrationTest/java/io/justsearch/ui/api/LocalApiIntegrationTestBase.java',
  'modules/ui/src/integrationTest/java/io/justsearch/ui/api/ProdModeSettingsPersistenceIntegrationTest.java',
  'modules/ui/src/test/java/io/justsearch/ui/api/DeclaredSurvivalHttpAdmissionTest.java',
  'modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatControllerTest.java',
  'modules/ui/src/test/java/io/justsearch/ui/api/McpOriginValidationTest.java',
];
function assertions(source, file) {
  if (file.endsWith('.java')) {
    // Balanced Java calls, preserving arguments; skip string literals and comments when balancing.
    const calls = [];
    for (const match of source.matchAll(/\bassert[A-Z]\w*\s*\(/g)) {
      let depth = 1, end = match.index + match[0].length;
      let quote = null, lineComment = false, blockComment = false;
      for (; end < source.length && depth; end++) {
        const c = source[end], n = source[end + 1];
        if (lineComment) { if (c === '\n') lineComment = false; continue; }
        if (blockComment) { if (c === '*' && n === '/') { blockComment = false; end++; } continue; }
        if (quote) { if (c === '\\') end++; else if (c === quote) quote = null; continue; }
        if (c === '/' && n === '/') { lineComment = true; end++; continue; }
        if (c === '/' && n === '*') { blockComment = true; end++; continue; }
        if (c === '"' || c === "'") quote = c;
        else if (c === '(') depth++;
        else if (c === ')') depth--;
      }
      calls.push({ line: source.slice(0, match.index).split('\n').length, text: source.slice(match.index, end).replace(/\s+/g, ' ') });
    }
    return calls;
  }
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
  const result = [];
  function rooted(n) {
    if (ts.isCallExpression(n) && ts.isIdentifier(n.expression) && n.expression.text === 'expect') return true;
    return (ts.isCallExpression(n) || ts.isPropertyAccessExpression(n)) && rooted(n.expression);
  }
  function visit(n) {
    if (ts.isCallExpression(n) && rooted(n) && !ts.isPropertyAccessExpression(n.parent)) {
      result.push({ line: ast.getLineAndCharacterOfPosition(n.getStart()).line + 1, text: n.getText(ast).replace(/\s+/g, ' ') });
    }
    ts.forEachChild(n, visit);
  }
  visit(ast);
  return result;
}
// Explicit observation substitutions: no expected value comes from production code.
function transportObservation(text) {
  return text
    .replaceAll('MockEventSource.instances', 'streams.connections')
    .replaceAll('NoopEventSource.urls', 'streams.connections.map((connection) => connection.url)')
    .replaceAll('source?.listenerTypes', 'listenerTypes()').replaceAll('source.listenerTypes', 'listenerTypes()')
    .replace(/expect\((es[AB])\.readyState\)\.toBe\(\1\.CLOSED\)/g, 'expect($1.closed).toBe(true)')
    .replace('exercise the token-exempt production GET', 'exercise the authenticated production GET');
}
const files = [...web, ...java].map(file => {
  const before = cp.execFileSync('git', ['show', `${baseline}:${file}`], { encoding: 'utf8' });
  const after = fs.readFileSync(file, 'utf8');
  const originScope = file.endsWith('/McpOriginValidationTest.java');
  const oldAssertions = assertions(originScope ? before.slice(0, before.indexOf('  // ---- GET /api/mcp/token')) : before, file);
  const remaining = assertions(originScope ? after.slice(0, after.indexOf('  @Test\n  @DisplayName("Removed token endpoint')) : after, file);
  const preserved = [], remapped = [], removed = [];
  for (const assertion of oldAssertions) {
    const index = remaining.findIndex(next => transportObservation(assertion.text) === next.text);
    if (index < 0) removed.push(assertion);
    else {
      const next = remaining.splice(index, 1)[0];
      (assertion.text === next.text ? preserved : remapped).push({ before: assertion, after: next });
    }
  }
  return { file, assertionScope: originScope ? "Surviving MCP/Origin assertions, retained in dev mode; identical production invocations are New; bootstrap expectations are Obsolete" : "whole file", beforeSha256: digest(before), afterSha256: digest(after), beforeCount: oldAssertions.length,
    afterCount: preserved.length + remapped.length + remaining.length, preserved, remapped,
    removed, added: remaining,
    diff: cp.execFileSync('git', ['diff', baseline, '--', file], { encoding: 'utf8' }) };
});
function digest(s) { return crypto.createHash('sha256').update(s).digest('hex'); }
const report = { schema: 'token-bootstrap-adaptation-audit.v1', baseline,
  candidate: cp.execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
  producerSourceSha256: digest(fs.readFileSync(new URL(import.meta.url), 'utf8')),
  executionAdapter: fs.readFileSync('gates/test-intent/evidence/token-bootstrap-execution-adapter.cjs', 'utf8'),
  executionEvidence: ['merged-web', 'engine', 'merged-base-preservation'].map(name => {
    const file = `gates/test-intent/evidence/token-bootstrap-${name}.json`;
    return { file, evidence: JSON.parse(fs.readFileSync(file, 'utf8')) };
  }),
  files, conclusion: 'Exact source inventories and transport substitutions; the verifier must also inspect full setup/input/tag diffs.' };
fs.writeFileSync('gates/test-intent/evidence/token-bootstrap-adaptation-audit.json', JSON.stringify(report, null, 2) + '\n');
for (const file of files) console.log(`${file.file}: ${file.beforeCount} -> ${file.afterCount}; ${file.remapped.length} observation mappings, ${file.removed.length} unmatched old, ${file.added.length} added`);
if (files.some(f => f.removed.length)) process.exitCode = 1;
