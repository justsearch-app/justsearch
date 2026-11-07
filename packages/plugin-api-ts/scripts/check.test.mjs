// Contract protection: actual commands, filesystem failures, local packs and consumers.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { replaceDirectory } from './replace-directory.mjs';

const pkg = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(pkg, '../..');
const require = createRequire(import.meta.url);
const tsc = require.resolve('typescript/bin/tsc');
const standardLib = dirname(require.resolve('typescript/lib/lib.es2022.d.ts')) + sep;
const npm = process.env.npm_execpath;
assert.ok(npm, 'run through npm run check (npm_execpath is required)');
const scratch = join(repo, 'tmp/plugin-api-check');
mkdirSync(scratch, { recursive: true });

function run(args, cwd, expected = 0) {
  const result = spawnSync(process.execPath, args, { cwd, encoding: 'utf8', timeout: 120_000, env: { ...process.env, npm_config_cache: join(scratch, 'npm-cache') } });
  if (result.error) throw result.error;
  assert.equal(result.status, expected, `${args.join(' ')}\n${result.stdout}\n${result.stderr}`);
  return result.stdout + result.stderr;
}
const npmRun = (cwd, command, expected = 0) => run([npm, 'run', command], cwd, expected);
function snapshot(directory) {
  return Object.fromEntries(readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? Object.entries(snapshot(path)).map(([name, bytes]) => [`${entry.name}/${name}`, bytes]) : [[entry.name, readFileSync(path).toString('base64')]];
  }).sort(([a], [b]) => a.localeCompare(b)));
}
function fixture() {
  const root = mkdtempSync(join(scratch, 'case-'));
  const sdk = join(root, 'packages/plugin-api-ts');
  mkdirSync(dirname(sdk), { recursive: true });
  cpSync(pkg, sdk, { recursive: true, filter: (path) => !['dist', 'node_modules'].includes(path.slice(path.lastIndexOf(sep) + 1)) && !path.includes(`${sep}.sdk-`) });
  for (const relative of ['shell-v0/plugin-api/plugin-types.ts', 'shell-v0/plugin-api/TrustChannel.ts', 'api/types/registry.ts', 'api/generated/registry-enums.generated.ts', 'api/generated/schema-types/resource.ts', 'api/generated/schema-types/presentation.ts', 'api/generated/schema-types/provenance.ts']) {
    const destination = join(root, 'modules/ui-web/src', relative);
    mkdirSync(dirname(destination), { recursive: true });
    cpSync(join(repo, 'modules/ui-web/src', relative), destination);
  }
  return { root, sdk, canonical: join(root, 'modules/ui-web/src') };
}
function mutate(path, transform) { writeFileSync(path, transform(readFileSync(path, 'utf8'))); }

// S1/S3: CI rejects checked-in drift before any fixture regeneration.
test('checked-in canonical projection is current', () => { npmRun(pkg, 'check:sync'); });

// S2/S3: use real sync command on disposable copies, never mutate live canonicals.
test('sync failures preserve every source byte; freshness is read-only and generation repeatable', () => {
  const { root, sdk, canonical } = fixture();
  try {
    npmRun(sdk, 'sync-types');
    const current = snapshot(join(sdk, 'src'));
    const beforeCheck = snapshot(sdk);
    npmRun(sdk, 'check:sync');
    assert.deepEqual(snapshot(sdk), beforeCheck);
    const trustPath = join(canonical, 'shell-v0/plugin-api/TrustChannel.ts');
    const trust = readFileSync(trustPath, 'utf8');
    const resourcePath = join(canonical, 'api/generated/schema-types/resource.ts');
    const resource = readFileSync(resourcePath, 'utf8');
    const failures = [
      () => rmSync(trustPath),
      () => writeFileSync(trustPath, 'export interface TrustVerdict {'),
      () => writeFileSync(trustPath, trust.replace('export interface TrustVerdict', 'export interface MissingVerdict')),
      () => writeFileSync(trustPath, trust + '\nexport interface TrustVerdict {}\n'),
      () => writeFileSync(trustPath, trust.replace('export interface TrustVerdict {', 'export class TrustVerdict {')),
      () => writeFileSync(resourcePath, resource.replace('endpoint: string;', 'endpoint: MissingEndpoint;')),
      () => writeFileSync(resourcePath, resource.replace('endpoint: string;', "endpoint: import('./missing.js').Endpoint;")),
    ];
    for (const fail of failures) {
      fail();
      npmRun(sdk, 'sync-types', 1);
      assert.deepEqual(snapshot(join(sdk, 'src')), current);
      writeFileSync(trustPath, trust);
      writeFileSync(resourcePath, resource);
    }
    const pluginPath = join(canonical, 'shell-v0/plugin-api/plugin-types.ts');
    mutate(pluginPath, (text) => text + '\n// canonical drift\n');
    npmRun(sdk, 'check:sync', 1);
    assert.deepEqual(snapshot(join(sdk, 'src')), current);
    npmRun(sdk, 'sync-types');
    const generated = snapshot(join(sdk, 'src'));
    npmRun(sdk, 'sync-types');
    assert.deepEqual(snapshot(join(sdk, 'src')), generated);
    const obsolete = join(sdk, 'src/support/obsolete.ts');
    writeFileSync(obsolete, '// Generated by scripts/sync-types.mjs from canonical host types. Do not edit.\nexport type Obsolete = string;\n');
    const custom = join(sdk, 'src/support/custom.ts');
    writeFileSync(custom, 'export type Custom = string;\n');
    npmRun(sdk, 'check:sync', 1);
    assert.ok(existsSync(obsolete));
    npmRun(sdk, 'sync-types');
    assert.ok(!existsSync(obsolete));
    assert.equal(readFileSync(custom, 'utf8'), 'export type Custom = string;\n');
    npmRun(sdk, 'check:sync');
  } finally { rmSync(root, { recursive: true, force: true }); }
});

// S2/S4: real rename failure after the old output has moved must restore it.
test('ordinary replacement failure restores previous output', () => {
  const root = mkdtempSync(join(scratch, 'replace-'));
  try {
    const old = join(root, 'output');
    mkdirSync(old);
    writeFileSync(join(old, 'usable'), 'previous output');
    const before = snapshot(old);
    assert.throws(() => replaceDirectory(join(root, 'missing-stage'), old), /ENOENT/);
    assert.deepEqual(snapshot(old), before);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

const consumer = `import { PLUGIN_CONTRACT_VERSION } from '@justsearch/plugin-api';
import type {
  PluginManifest, PluginCapabilities, PluginRegisterFn, PluginUnregisterFn,
  PluginHostApi, PluginSurfaceContribution, SurfacePortHandler, SurfacePortContext,
  PluginTrustTier, TrustVerdict, TrustEvidence, TrustChannel, PluginContribution,
} from '@justsearch/plugin-api';
const version: '1.1' = PLUGIN_CONTRACT_VERSION;
type OriginalTypes = [PluginManifest, PluginCapabilities, PluginRegisterFn, PluginUnregisterFn,
  PluginHostApi, PluginSurfaceContribution, SurfacePortHandler, SurfacePortContext,
  PluginTrustTier, TrustVerdict, TrustEvidence, TrustChannel];
type ResourceContribution = NonNullable<PluginContribution['resourceContributions']>[number]['contribution'];
const resource: ResourceContribution = {
  id: 'vendor.example.state', audience: 'USER', category: 'STATE', endpoint: '/api/example',
  kind: 'example', primaryKey: '', schema: 'https://example.invalid/schema', role: 'PRODUCT',
  subscriptionMode: 'ONE_SHOT', presentation: { labelKey: 'example.label', descriptionKey: 'example.description' },
  history: null, privacy: { loopbackOnly: true, pathPolicy: 'NO_PATHS', resolver: null },
  consumers: [{ consumerId: 'example.panel', audience: 'USER' }],
  recovery: null, collectionOperations: [], itemOperations: [], emissionPolicy: null,
};
const contribution: PluginContribution = { resourceContributions: [{ contribution: resource, version: '1' }] };
const manifest: PluginManifest = {
  id: 'example', version: '1', displayName: 'Example', contractVersion: version,
  tagNamespace: 'example', capabilities: {}, activate: async () => {}, deactivate: () => {},
  register(host) {
    host.registration.registerSurfacePort('example.panel', (context) => null);
    host.registration.registerCommand('example.open', 'Open', () => {});
    host.data.subscribeResource(resource.id, () => {});
    return contribution;
  },
};
const verdict: TrustVerdict = { tier: 'TRUSTED_PLUGIN', explanation: 'test', identity: null };
// @ts-expect-error invalid audience
const badAudience: ResourceContribution['audience'] = 'ADMIN';
// @ts-expect-error invalid endpoint
const badEndpoint: ResourceContribution['endpoint'] = 42;
// @ts-expect-error invalid resource consumer audience
const badConsumer: ResourceContribution['consumers'][number]['audience'] = 'ADMIN';
// @ts-expect-error invalid trust tier
const badTier: PluginTrustTier = 'VERIFIED';
// @ts-expect-error invalid trust verdict
const badVerdict: TrustVerdict['tier'] = 'VERIFIED';
// @ts-expect-error grouped host API has no root registration function
const badRegistration: PluginHostApi['registerSurfacePort'] = () => {};
`;

// S1/S3-S7/SP1-SP3: actual package scripts, tarball installation, both loaders,
// and compiler-resolved-file evidence preventing monorepo types from hiding leaks.
test('built local pack supports both Node loaders and isolated strict consumers', () => {
  const { root, sdk } = fixture();
  try {
    npmRun(sdk, 'prepublishOnly');
    const before = snapshot(join(sdk, 'dist'));
    const index = join(sdk, 'src/index.ts');
    const indexText = readFileSync(index, 'utf8');
    writeFileSync(index, indexText + '\nconst broken: MissingBuildType = 1;\n');
    npmRun(sdk, 'build', 1);
    assert.deepEqual(snapshot(join(sdk, 'dist')), before);
    writeFileSync(index, indexText);
    writeFileSync(join(sdk, 'dist/obsolete.js'), 'stale');
    npmRun(sdk, 'sync-types');
    npmRun(sdk, 'build');
    npmRun(sdk, 'typecheck');
    assert.deepEqual(snapshot(join(sdk, 'dist')), before);
    const pack = JSON.parse(run([npm, 'pack', '--json', '--ignore-scripts', '--pack-destination', root], sdk))[0];
    const client = join(root, 'consumer');
    mkdirSync(client);
    writeFileSync(join(client, 'package.json'), JSON.stringify({ private: true }));
    run([npm, 'install', '--offline', '--ignore-scripts', '--package-lock=false', '--audit=false', '--fund=false', join(root, pack.filename)], client);
    const installed = realpathSync(join(client, 'node_modules/@justsearch/plugin-api'));
    writeFileSync(join(client, 'load.mjs'), `import assert from 'node:assert/strict'; import * as sdk from '@justsearch/plugin-api'; assert.deepEqual(Object.keys(sdk), ['PLUGIN_CONTRACT_VERSION']); assert.equal(sdk.PLUGIN_CONTRACT_VERSION, '1.1');`);
    writeFileSync(join(client, 'load.cjs'), `const assert = require('node:assert/strict'); const sdk = require('@justsearch/plugin-api'); assert.deepEqual(Object.keys(sdk), ['PLUGIN_CONTRACT_VERSION']); assert.equal(sdk.PLUGIN_CONTRACT_VERSION, '1.1');`);
    run(['load.mjs'], client);
    run(['load.cjs'], client);
    assert.throws(() => require.resolve('@justsearch/plugin-api/dist/support/registry', { paths: [client] }), /ERR_PACKAGE_PATH_NOT_EXPORTED/);
    for (const [resolution, module, extension] of [['Bundler', 'ESNext', 'ts'], ['NodeNext', 'NodeNext', 'mts'], ['NodeNext', 'NodeNext', 'cts']]) {
      const file = join(client, `consumer.${extension}`);
      writeFileSync(file, consumer);
      writeFileSync(join(client, 'tsconfig.json'), JSON.stringify({ compilerOptions: { target: 'ES2022', module, moduleResolution: resolution, strict: true, skipLibCheck: false, types: [], noEmit: true, lib: ['ES2022', 'DOM'] }, files: [file] }));
      const list = run([tsc, '-p', 'tsconfig.json', '--listFiles'], client);
      writeFileSync(join(scratch, `resolved-${resolution}-${extension}.log`), list);
      const resolved = list.trim().split(/\r?\n/).map((path) => realpathSync(path));
      assert.ok(resolved.some((path) => path.startsWith(installed + sep)), 'must resolve the installed pack');
      for (const path of resolved) assert.ok(path === file || path.startsWith(installed + sep) || (path.startsWith(standardLib) && /lib\.[^/\\]+\.d\.ts$/.test(path)), `unexpected consumer dependency: ${path}`);
    }
    const compatibility = join(client, 'canonical.ts');
    writeFileSync(compatibility, `import type { PluginContribution as SDKContribution, PluginHostApi as SDKHost } from '@justsearch/plugin-api';
import type { PluginContribution as CanonicalContribution, PluginHostApi as CanonicalHost } from '${join(repo, 'modules/ui-web/src/shell-v0/plugin-api/plugin-types.js').replaceAll('\\', '/')}';
import type { Resource } from '${join(repo, 'modules/ui-web/src/api/types/registry.js').replaceAll('\\', '/')}';
type SDKResource = NonNullable<SDKContribution['resourceContributions']>[number]['contribution'];
type Assert<T extends true> = T;
type Both<A, B> = [A] extends [B] ? [B] extends [A] ? true : false : false;
type ContributionsAgree = Assert<Both<SDKContribution, CanonicalContribution>>;
type HostsAgree = Assert<Both<SDKHost, CanonicalHost>>;
type ResourcesAgree = Assert<Both<SDKResource, Omit<Resource, 'provenance'>>>;
// @ts-expect-error canonical resource provenance rejects invalid trust tier
const badTier: Resource['provenance']['tier'] = 'VERIFIED';
`);
    writeFileSync(join(client, 'tsconfig.json'), JSON.stringify({ compilerOptions: { target: 'ES2022', module: 'ESNext', moduleResolution: 'Bundler', strict: true, skipLibCheck: false, types: [], noEmit: true, esModuleInterop: true, lib: ['ES2022', 'DOM'] }, files: [compatibility] }));
    run([tsc, '-p', 'tsconfig.json'], client);
    console.log('Packed ESM/CJS, strict Bundler/NodeNext, canonical compatibility passed');
  } finally { rmSync(root, { recursive: true, force: true }); }
});
