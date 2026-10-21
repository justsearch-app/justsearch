#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const LANES = Object.freeze(['build', 'license', 'ui', 'services', 'search', 'platform', 'windows', 'devrunner', 'rust', 'lifecycle', 'integration', 'jseval']);
const java = ['build', 'ui', 'services', 'search', 'platform', 'windows', 'lifecycle', 'integration'];
const contracts = [...java, 'rust', 'jseval', 'devrunner'];
// Unclaimed paths deliberately select everything. Rules overlap: a mixed diff and
// a file consumed by several lanes both take the union, never first-match wins.
export const RULES = [
  [/^(?:docs\/|\.claude\/|\.codex\/|\.agents\/|AGENTS\.md$|[^/]+\.md$)/, []],
  [/^modules\/(?:adapters-lucene|ai-backend|api-contract-projection-java|app-agent|app-agent-api|app-api|app-config|app-engine|app-inference|app-observability|app-services|app-util|configuration|core|core-contracts|extension-substrate|gpu-bridge|indexer-worker|indexing|infra-core|ipc-common|ort-common|prompt-support|reranker|telemetry|worker-core|worker-services)\//, java],
  [/^modules\/ui\//, java.filter((lane) => lane !== 'search')],
  [/^modules\/system-tests\//, ['build', 'services', 'platform', 'integration', 'lifecycle']],
  [/^modules\/(?:app-api-tck|app-launcher|benchmarks|dead-code-audit|ssot-tools|test-support)\//, ['build', 'services', 'platform']],
  [/^modules\/ui-web\//, ['build', 'ui', 'services', 'lifecycle', 'integration']],
  [/^modules\/ui-web\/(?:scripts\/|[^/]*(?:config|package)[^/]*$)/, ['devrunner']],
  [/^modules\/ui-web\/src\/api\/backendRestart\.ts$/, ['rust']],
  [/^modules\/app-services\/src\/test\/resources\/policy\/app-update-policy-cases\.json$/, ['rust']],
  [/^(?:modules\/shell\/|native\/)/, ['build', 'rust', 'services', 'platform', 'windows', 'devrunner', 'lifecycle', 'integration']],
  [/^(?:build-logic\/|gradle\/|gradlew|settings\.gradle|build\.gradle|gradle\.properties)/, contracts],
  [/^(?:schemas?\/|contracts?\/|catalog\/|ssot\/|scripts\/(?:codegen|wire-contract)\/)/, contracts],
  [/^(?:packages\/runtime-client\/)/, ['build', 'ui', 'services', 'lifecycle', 'integration']],
  [/^(?:scripts\/jseval\/)/, ['jseval', 'services', 'platform', 'lifecycle', 'integration']],
  [/^(?:scripts\/dev\/(?:.*dev-runner.*|.*supervisor.*)|scripts\/supervisor-conformance\/|scripts\/lib\/|scripts\/.*launcher.*|dev-runner\.cjs$)/, ['devrunner', 'windows', 'rust', 'lifecycle', 'integration']],
  [/^(?:scripts\/release\/|scripts\/ci\/(?:test-.*(?:sign|codesign|release-compatibility|pe-signatures).*|.*release-signing.*))/, ['windows', 'lifecycle', 'integration']],
  [/^governance\/supervision-contract\.v1\.json$/, ['services', 'rust', 'devrunner', 'windows', 'lifecycle', 'integration']],
  [/^governance\/(?:store-recoverability\.v1\.json|exit-ledger\.v1\.schema\.json)$/, ['rust', 'devrunner']],
  [/^governance\/platform-paths/, ['rust', 'windows', 'devrunner']],
  [/^gates\/config-surface\//, ['platform', 'services']],
  [/^(?:governance\/|gates\/)/, ['services', 'platform', 'jseval']],
  [/^(?:package(?:-lock)?\.json$)/, ['build', 'license', 'devrunner', 'ui', 'services']],
  [/(?:^|\/)(?:package(?:-lock)?\.json|Cargo\.(?:toml|lock)|[^/]*\.gradle\.kts|gradle\.lockfile|libs\.versions\.toml)$/, ['license']],
  [/^(?:modules\/configuration\/src\/main\/resources\/ai\/model-registry\.v2\.json|scripts\/codegen\/(?:gen-notices\.mjs|.*license.*\.json)|packaging\/runtime\/(?:tesseract-.*\.json|NOTICE-MODELS\.txt)|THIRD_PARTY_NOTICES)$/, ['license']],
  [/^(?:models\/|licenses?\/|notices?\/|NOTICE|LICENSE|native-licenses\/)/, ['license']],
  [/^docs\/reference\/legal\/ai-runtime-and-model-redistribution\.md$/, ['license']],
];

export function classifyPaths(paths, eventName = 'pull_request') {
  const selected = Object.fromEntries(LANES.map((lane) => [lane, false]));
  const all = () => Object.fromEntries(LANES.map((lane) => [lane, true]));
  if (eventName !== 'pull_request' || !Array.isArray(paths) || !paths.length) return all();
  for (const path of paths) {
    if (typeof path !== 'string' || !path || path.startsWith('.github/')) return all();
    const rules = RULES.filter(([pattern]) => pattern.test(path));
    if (!rules.length) return all();
    for (const [, lanes] of rules) for (const lane of lanes) selected[lane] = true;
  }
  return selected;
}

export function classifyDiff({ eventName, base, head, git = (args) => execFileSync('git', args, { encoding: 'utf8' }) }) {
  if (eventName !== 'pull_request') return classifyPaths([], eventName);
  try {
    if (!/^[a-f0-9]{40}$/.test(base ?? '') || !/^[a-f0-9]{40}$/.test(head ?? '')) throw new Error('missing event commits');
    // -z preserves tabs/newlines in paths; --no-renames exposes both old and new paths.
    const fields = git(['diff', '--name-status', '--no-renames', '-z', `${base}...${head}`]).split('\0');
    if (fields.pop() !== '' || !fields.length || fields.length % 2) throw new Error('incomplete diff');
    const paths = [];
    for (let i = 0; i < fields.length; i += 2) {
      if (!/^[ACDMRTUXB]$/.test(fields[i])) throw new Error('malformed diff status');
      paths.push(fields[i + 1]);
    }
    return classifyPaths(paths, eventName);
  } catch (error) {
    console.error(`changes: selecting every lane: ${error.message}`);
    return classifyPaths([]);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const lanes = classifyDiff({ eventName: process.env.GITHUB_EVENT_NAME, base: process.env.PR_BASE_SHA, head: process.env.PR_HEAD_SHA });
  const output = Object.entries(lanes).map(([lane, value]) => `${lane}=${value}`).join('\n') + '\n';
  process.stdout.write(output);
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, output);
}
