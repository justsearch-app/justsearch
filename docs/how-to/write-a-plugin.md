---
title: Writing a JustSearch plugin
type: how-to
status: stable
description: "Author a V1.5 plugin that runs in a SES Compartment, contributes a Surface, and integrates with the host's trust + capability model."
---

# Writing a JustSearch plugin

This guide walks through writing a complete V1.5 plugin from
scratch. Before you start, you should be familiar with:

- JavaScript ES2022+ (the plugin contract is JS, not TS — TS authors
  compile down before shipping).
- Web Components / Custom Elements API (plugins typically contribute
  one or more `<my-plugin-foo>` elements).
- The truth/presentation boundary
  ([ADR-0035](../decisions/0035-fe-plugin-boundary.md))
  — plugins extend presentation, never own backend truth.

## What a plugin is

A V1.5 plugin is a single JavaScript file that:

1. **Loads in a SES Compartment** — separate JS realm; the plugin
   cannot reach `window`, `fetch`, the host's `localStorage`, or
   any other global unless explicitly endowed by the host.
2. **Returns a typed manifest** — id, version, capabilities, and a
   `register(host)` callback the host invokes after install.
3. **Contributes a Surface** (optional but typical) — a custom
   element that mounts in the chrome's rail/stage.
4. **Runs at a trust tier** — `UNTRUSTED_PLUGIN` by default;
   `TRUSTED_PLUGIN` requires backend signature verification
   (V1.5.2+ via Sigstore).

The architectural keystone is the truth/presentation boundary
([ADR-0035](../decisions/0035-fe-plugin-boundary.md)) realized by the
extension substrate (tempdoc 560):
trust is a *type* produced at the delivery seam (verdict-at-the-seam); it's
not a documented invariant the plugin author has to read about.

## The plugin source contract

V1.5.1 plugins ship as a JavaScript **expression** — NOT an ES
module. The expression evaluates to either:

- A `PluginManifest` object directly, OR
- A factory function `(endowments) => PluginManifest`.

**Factory shape is preferred.** It makes the endowment contract
explicit and lets you define classes that close over the endowed
primitives.

### Minimal factory plugin

There are two register-shape options. Prefer the §4.I
(declaration-record) shape for new plugins — atomic install +
host-side namespace validation. The legacy imperative shape is
preserved for back-compat.

#### Option A — §4.I declaration-record shape (preferred)

```js
// myplugin.js — V1.5.1 §4.I-shape plugin
(({ HTMLElement }) => {
  class MyPluginPanel extends HTMLElement {
    connectedCallback() {
      this.innerHTML = `
        <div style="padding: 1rem">
          <h2>My Plugin</h2>
          <p>Hello from a Compartment-isolated realm.</p>
        </div>
      `;
    }
  }
  // NOTE: no customElements.define() at factory-evaluate. The
  // host registers the class via the PluginContribution record
  // returned by register() below.

  return {
    id: 'myplugin',
    version: '1.0.0',
    displayName: 'My Plugin',
    contractVersion: '1.1',
    tagNamespace: 'myplugin',     // MUST equal id
    capabilities: {
      surfaces: [
        {
          id: 'myplugin.panel',
          mountTag: 'myplugin-panel',
          labelKey: 'myplugin.panel.label',
          descriptionKey: 'myplugin.panel.description',
          audience: 'USER',
          placement: 'RAIL',
          consumes: {
            operations: [],
            resources: [],
            prompts: [],
            diagnosticChannels: [],
          },
        },
      ],
    },
    register(host) {
      void host;
      // §4.I: return a typed PluginContribution. The registry
      // applies all sub-contributions atomically. If a sub-step
      // fails (e.g., invalid tag suffix), NO partial state lands
      // (the plugin records `registerError` and dispatch skips it).
      return {
        customElements: [
          { tagSuffix: 'panel', klass: MyPluginPanel },
        ],
        translations: {
          en: {
            'myplugin.panel.label': 'My Plugin',
            'myplugin.panel.description': 'A V1.5 hello-world plugin',
          },
        },
      };
    },
  };
});
```

#### Option B — Legacy imperative shape (V1.5 alpha back-compat)

```js
// myplugin.js — V1.5 alpha imperative-shape plugin
(({ customElements, HTMLElement }) => {
  class MyPluginPanel extends HTMLElement { /* ... */ }
  if (!customElements.get('myplugin-panel')) {
    customElements.define('myplugin-panel', MyPluginPanel);
  }
  return {
    id: 'myplugin',
    version: '1.0.0',
    // ... same other fields
    translations: {
      en: { /* keys */ },
    },
    register(host) {
      void host;  // returning void preserves V1.5 alpha behavior
    },
  };
});
```

The two shapes are interchangeable from the host's perspective.
§4.I gives you atomicity + clearer errors; legacy works unchanged.

### What the host endows

The default endowment bundle (`buildDefaultEndowments`) provides:

- `customElements`, `HTMLElement` — register custom elements
- `localStorage`, `document` — DOM/storage primitives
- `setTimeout`, `clearTimeout`, `setInterval`, `clearInterval` — timers
- `console` — diagnostic output

The default bundle does NOT include:

- `fetch` — V1.5 plugins must use host-mediated network
  (host-mediated capability per the extension substrate, tempdoc 560)
- `window`, `globalThis` — host realm globals are unreachable
- `import` / `export` syntax — Compartment.evaluate is script-mode,
  not module-mode. Sub-module imports ship in V1.5.2+.

Tier-attenuated bundles (`UNTRUSTED_PLUGIN` vs `TRUSTED_PLUGIN`)
swap in scoped wrappers:

| Endowment | TRUSTED_PLUGIN | UNTRUSTED_PLUGIN |
|---|---|---|
| `localStorage` | Raw `Storage` | Scoped facade — keys auto-prefixed `plugin:<id>:` |
| `customElements` | Raw `CustomElementRegistry` | Proxy — rejects `define()` for tags outside `<id>-*` |
| Other | Same as TRUSTED | Same as TRUSTED (V1.5.1 alpha; V1.5.2 may differ) |

## Hosting and loading the plugin

V1.5.1 alpha doesn't ship a marketplace. To load your plugin:

### During development — Vite dev-examples

Drop your plugin file at
`modules/ui-web/dev-examples/<your-plugin>/plugin.js`. The
`devExamplesMiddleware` in `vite.config.js` serves it at
`/dev-examples/<your-plugin>/plugin.js`. Then in the browser
console:

```js
const mod = await import('/src/shell-v0/plugin-api/index.ts');
const reg = mod.getSessionPluginRegistry();
const m = await mod.loadPluginFromUrl(
  reg,
  '/dev-examples/myplugin/plugin.js',
  { expectedPluginId: 'myplugin' },
);
console.log('installed:', m.id);

// Merge the surface into the catalog so it appears in the rail:
const catalog = await import('/src/api/registry/SurfaceCatalogClient.ts');
catalog.mergePluginSurfaceContributions(reg.surfaceContributions());
```

The plugin's surface should now appear in the activity rail.

### Listing and revoking plugins via Settings

Open the Settings surface (rail icon at the bottom). The
**Plugins** section lists all installed plugins with their
provenance and an "Revoke" button per row. Revoke calls
`registry.uninstall` + `removePluginSurfaceContributions` to
fully remove the plugin's contributions from the running shell.

### Production loading (V1.5.2+)

V1.5.2's marketplace UI is the production load path. Until then,
plugins are dev-tooling-only.

## Trust tiers in detail

The host computes a plugin's trust tier via `TrustChannel.verify()`
(verdict-at-the-seam; tempdoc 560).
V1.5.1 alpha ships two channel implementations:

- **`StubTrustChannel`** — always returns `UNTRUSTED_PLUGIN`. Used
  as the loader's default in V1.5.1 (no signed plugins exist yet).
- **`RemoteTrustChannel`** — calls `POST /api/plugins/verify` on the
  Java backend. It returns `verified: true` for SHA-256 artifacts on the operator's
  persistent allowlist, and `verified: false` otherwise. The operator manages entries through
  `POST`/`GET /api/plugins/allowlist` and `DELETE /api/plugins/allowlist/{sha}`.
  This is an explicit operator trust decision; Sigstore chain verification is not implemented.

When a host uses `RemoteTrustChannel`, an operator-allowlisted artifact can receive
`TRUSTED_PLUGIN` capabilities (raw localStorage, unrestricted custom-element names).
The default `StubTrustChannel` still yields `UNTRUSTED_PLUGIN`; adding an allowlist entry
alone does not change a loader that uses the stub. `CORE` is reserved for host-shipped,
compiled-in plugins.

## Common pitfalls

### "ReferenceError: window is not defined"

You're trying to read a host realm global. Either:
- Endow it explicitly via `endowmentsExtension` in the loader call,
- Refactor to use an endowed global (`document` instead of `window.document`),
- Or pass the data via the `register(host)` callback's `host` parameter.

### "Plugin '<id>' attempted to define '<tag>'..."

You're at `UNTRUSTED_PLUGIN` tier and your `customElements.define(tag, ...)`
tag doesn't start with your plugin id followed by `-`. Fix the tag.

### "manifest.id mismatch with expectedPluginId"

The loader's `expectedPluginId` option must match the manifest's
`id` field exactly. If you changed the id, update both.

### Plugin appears installed but not in the rail

After `loadPluginFromUrl`, you must call
`mergePluginSurfaceContributions(registry.surfaceContributions())`
to merge the surface into the live catalog. V1.5.2 wires this
into the loader's success path automatically.

### CSS @import / @layer won't work in plugin styles

Plugin classes that set `innerHTML` with inline `<style>` tags
are fine. Plugin-injected `<style>` elements participate in the
host's cascade — they're subject to the layer order
(`core-theme, user-theme, user-override`). Plugin-tier theme
contributions are V1.5.2+.

## Reference plugin

The canonical V1.5.1 reference plugin is
`modules/ui-web/dev-examples/plugin-scratch-notes/plugin.js`.
It's a complete factory-shape plugin demonstrating:

- Custom element class extending the endowed `HTMLElement`
- localStorage persistence (works under both TRUSTED and
  UNTRUSTED-scoped facades unchanged)
- Surface contribution that mounts in the rail
- i18n translations for label + description keys
- A no-op `register(host)` (no host-mediated APIs needed)

Read it end-to-end before authoring your own. It's ~150 lines
and well-commented.

## Architecture references

- [ADR-0035 — Plugin boundary (truth vs presentation)](../decisions/0035-fe-plugin-boundary.md)
- [ADR-0033 — Frontend as a framework, not a product](../decisions/0033-fe-framework-not-product.md)
- [ADR-0034 — Backend-owned truth](../decisions/0034-fe-backend-owned-truth.md)
- tempdoc 560 — Extension substrate (contribution projection + delivery; trust at the seam)
- tempdoc 569 — User-authored frontend (presentation as a projection)
- [Frontend substrate-state map](../reference/ui/frontend-substrate-state.md)

> The V1.5 plugin-substrate design slices (464/470/471/477/478) that this guide was
> originally drafted against lived in the retired `421` kernel draft; their decisions are
> captured canonically above (ADR-0035 + the extension substrate, tempdoc 560), and the
> forward design is tempdoc 569. The original slices remain in git history.

## Substrate code references

- `modules/ui-web/src/shell-v0/plugin-api/plugin-types.ts` —
  `PluginManifest`, `PluginCapabilities`, `PluginHostApi`
- `modules/ui-web/src/shell-v0/plugin-api/PluginLoader.ts` —
  source-text fetch + `Compartment.evaluate` pipeline
- `modules/ui-web/src/shell-v0/plugin-api/PluginCompartment.ts` —
  `buildDefaultEndowments`, `ensureSesLoaded`
- `modules/ui-web/src/shell-v0/plugin-api/PluginCapabilityBundle.ts` —
  per-tier endowment attenuation
- `modules/ui-web/src/shell-v0/plugin-api/TrustChannel.ts` —
  `StubTrustChannel`, `RemoteTrustChannel`
- `modules/ui-web/src/shell-v0/plugin-api/PluginRegistry.ts` —
  install / uninstall / surface-contribution enumeration
- `modules/ui-web/dev-examples/plugin-scratch-notes/plugin.js` —
  reference implementation
