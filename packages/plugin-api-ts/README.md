# @justsearch/plugin-api

TypeScript authoring SDK for the frozen JustSearch plugin contract (host contract
version `1.1`). This maintenance package does not activate future plugin features
or change the host runtime. Node 20 or newer is supported.

## Contents

The package includes ESM (`dist/index.mjs`), CommonJS (`dist/index.js`), and strict,
self-contained declarations (`dist/index.d.ts`). Import or require the package by
name; both runtime formats export only `PLUGIN_CONTRACT_VERSION`. The private
support declarations carry the canonical resource vocabulary without shipping
host implementations, trust verification code, or schema validators.

The original public names remain available: `PLUGIN_CONTRACT_VERSION`,
`PluginManifest`, `PluginCapabilities`, `PluginRegisterFn`, `PluginUnregisterFn`,
`PluginHostApi`, `PluginSurfaceContribution`, `SurfacePortHandler`,
`SurfacePortContext`, `PluginTrustTier`, `TrustVerdict`, `TrustEvidence`, and
`TrustChannel`. Current canonical declarations also include `PluginContribution`,
resource contributions, lifecycle hooks, and the grouped host APIs.

```ts
import type { PluginManifest } from '@justsearch/plugin-api';

const manifest: PluginManifest = {
  id: 'myplugin',
  version: '1.0.0',
  displayName: 'My Plugin',
  contractVersion: '1.1',
  tagNamespace: 'myplugin',
  capabilities: {},
  register(host) {
    host.registration.registerSurfacePort('myplugin.panel', () => null);
    return { translations: { en: { 'myplugin.title': 'My Plugin' } } };
  },
};
export default manifest;
```

Host access is grouped, for example `host.registration.registerSurfacePort`,
`host.data.fetch`, and `host.search.subscribeSearch`. A register hook may return a
`PluginContribution`; activate/deactivate hooks are separate lifecycle hooks.
Resource contributions use the canonical registry shape; the host owns provenance.

See the [plugin authoring guide](https://github.com/justsearch-app/justsearch/blob/main/docs/how-to/write-a-plugin.md)
for the SES factory-expression source format and host trust rules. SDK runtime
bundles are authoring conveniences; they do not provide a plugin loader.

## Maintaining the snapshot

From a monorepo checkout, first install the root dependencies with `npm ci`.
Scripts resolve the repository's pinned local TypeScript and esbuild; no tool is
downloaded as a fallback. Run these from the repository root:

```sh
npm --prefix packages/plugin-api-ts run sync-types
npm --prefix packages/plugin-api-ts run check:sync
npm --prefix packages/plugin-api-ts run build
npm --prefix packages/plugin-api-ts run typecheck
npm --prefix packages/plugin-api-ts run check
npm --prefix packages/plugin-api-ts run prepublishOnly
```

Sync projects a fixed set of canonical declarations using TypeScript's parser and
typechecks staged source before replacement. It preserves other source files and
removes stale generated declarations. `check:sync` is read-only. Build stages both
runtime formats and declarations before replacing `dist`. Ordinary replacement
failures restore previous output or identify a recoverable backup in the error;
these directory swaps do not promise crash-atomic updates.

The package check exercises real local packs, both Node loaders, isolated strict
Bundler/NodeNext consumers, canonical assignability, freshness, and failure
preservation. `prepublishOnly` prepares and checks artifacts; run it locally
without publishing. Publication and contract evolution remain separate work.

## License

Apache-2.0.
