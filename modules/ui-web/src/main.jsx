// Slice 466 — SES lockdown at boot (OPT-IN gated for V1.5 alpha).
//
// Per slices/470-v1-5-user-ui-authorship-substrate.md §B.C.5
// (live-smoke verified 2026-05-07): lockdown() is compatible with
// Lit + Vite + the V1.5 PluginLoader in the validated environment
// (Vite dev server, mock backend, Chromium WebView). It is NOT yet
// verified across:
//   - Real backend traffic through the capability handshake
//   - Tauri WebView2 / WKWebView / WebKitGTK production builds
//   - All seven core surfaces under load
// Until V1.5.1 polish runs that verification, lockdown() is
// opt-in via either:
//   - URL parameter `?lockdown=1` (per-session, dev/QA)
//   - Build-time env `VITE_SES_LOCKDOWN=1` (CI / production opt-in)
// Default boot remains the un-frozen realm; the SES Compartment
// factory still ships and can be used per-plugin without lockdown.
//
// Compiled-code `import()` continues to function post-lockdown —
// verified end-to-end with the PluginLoader loading a `data:` URL
// plugin. Only arbitrarily-eval'd code's `import()` is rejected
// (SES_IMPORT_REJECTED) — desired behavior since that's the
// capability-leak vector SES exists to close.
//
// Conservative taming options preserve common patterns:
//   - errorTaming: 'unsafe'  → keep Error.captureStackTrace etc.
//   - consoleTaming: 'unsafe' → don't replace console (Vite HMR uses it)
//   - domainTaming: 'unsafe' → don't fight Node's deprecated `domain`
//   - overrideTaming: 'severe' → tolerate prototype-property assignments
// Slice 477 H2.6 — SES bundle code-splitting.
// SES is no longer eagerly imported here. The PluginLoader
// dynamically imports it on first plugin load. Users who never
// load a plugin don't pay the ~80 kB SES cost on cold start.
//
// Tempdoc 560 Fix C: SES lockdown is ON BY DEFAULT — the §4.4 / P2 isolation end-state. Untrusted
// plugins must run in a realm with frozen intrinsics, otherwise prototype pollution defeats the
// capability attenuation (and the removed `document` endowment). The only escape hatch is an explicit
// opt-OUT (`?lockdown=0` URL or VITE_SES_LOCKDOWN=0 env) for debugging a lockdown-incompatibility
// regression. The dynamic import inside the function is well-supported (esbuild rejects only top-level
// `await`).
const __sesLockdownDisabled =
  (typeof window !== 'undefined' &&
    new URLSearchParams(window.location.search).get('lockdown') === '0') ||
  import.meta.env.VITE_SES_LOCKDOWN === '0'
async function __maybeLockdown() {
  if (__sesLockdownDisabled) return
  await import('ses')
  // `lockdown` is the SES global injected by importing ses above;
  // declared in eslint.config.js globals + src/types/ses-globals.d.ts.
  lockdown({
    errorTaming: 'unsafe',
    consoleTaming: 'unsafe',
    domainTaming: 'unsafe',
    overrideTaming: 'severe',
  })
}
// Fire-and-forget — bootstrap below runs in parallel. The SES fetch adds a tiny startup latency;
// the security benefit (frozen intrinsics for every plugin realm) dwarfs it.
void __maybeLockdown()

import './styles/tokens.css'
// Register local strategies immediately; catalog requests start after API discovery.
import { startMessageCatalogs } from './i18n'
// Slice 449 phase 11 — Lit chrome is the production chrome. The React `<App>`
// + `<GlassShell>` + `<ActivityRail>` + `<Stage>` and the React rail-view
// components were decommissioned in this slice; the shell-v0 barrel registers
// every <jf-*> custom element + the chrome (<jf-shell> + <jf-rail> +
// <jf-stage>) at module load.
import './shell-v0/index.ts'
import { appLog } from './utils/logger.ts'
import { authorizedFetch } from './shell-v0/api/authorizedFetch.ts'

// Global unhandled error capture — log-only, no UI notification
window.addEventListener('unhandledrejection', (event) => {
  appLog.error('Unhandled promise rejection', {
    reason: event.reason instanceof Error
      ? { message: event.reason.message, stack: event.reason.stack }
      : event.reason,
  });
});

window.onerror = (message, source, lineno, colno, error) => {
  appLog.error('Uncaught error', {
    message,
    source,
    lineno,
    colno,
    stack: error?.stack,
  });
};

async function bootstrap() {
  const params = new URLSearchParams(window.location.search);

  // Substrate-isolation debug route: standalone Lit docking shell demo.
  if (params.get('shell-demo') === '1') {
    const { mountShellDemo } = await import('./shell-v0/demo/shell-demo.ts')
    mountShellDemo(document.getElementById('root'))
    return
  }
  // Substrate-isolation debug route: HealthLitView Conditions stream.
  if (params.get('lit-health') === '1') {
    const { mountHealthLitDemo } = await import('./shell-v0/demo/health-lit-demo.ts')
    mountHealthLitDemo(document.getElementById('root'))
    return
  }
  // Substrate-isolation debug route: TIMESERIES cohort.
  if (params.get('lit-health-trends') === '1') {
    const { mountHealthLitTrendsDemo } = await import('./shell-v0/demo/health-lit-trends-demo.ts')
    mountHealthLitTrendsDemo(document.getElementById('root'))
    return
  }
  // 569 — the user-authored frontend demo (engine + §9 spike + statechart + quarantine).
  if (params.get('presentation-demo') === '1') {
    const { mountPresentationDemo } = await import('./shell-v0/demo/presentation-demo.ts')
    mountPresentationDemo(document.getElementById('root'))
    return
  }

  // Production boot: <jf-shell> directly. SurfaceCatalog booted up-front
  // so the rail has data on first paint.
  const { resolveBootWithRecovery } = await import('./boot/desktopRecovery.ts')
  const resolvedApiBase = await resolveBootWithRecovery(document.getElementById('root'))
  if (!resolvedApiBase) {
    appLog.error('Unable to resolve JustSearch backend API base')
    return
  }
  const { bootSurfaceRegistry } = await import('./api/registry/SurfaceCatalogClient.ts')
  const apiBase = resolvedApiBase
  startMessageCatalogs(apiBase)
  try {
    await bootSurfaceRegistry(apiBase)
  } catch {
    // Catalog boot is best-effort; rail re-renders when the listener fires
    // after a successful background re-fetch.
  }

  // Tempdoc 508 §3.2 — boot operation catalog so commands can project
  // operations into the palette.
  try {
    const { bootOperationRegistry, listOperations, onCatalogChange } = await import('./api/registry/OperationCatalogClient.ts')
    await bootOperationRegistry(apiBase)
    // Tempdoc 560 WS3 — bridge backend-declared operation inverses
    // (OperationPolicy.inverseOperationId) into the Effect Journal so undoing a
    // backend operation re-issues its declared inverse. Wired at the composition
    // root to keep the api/ layer free of a shell-v0 import. Re-syncs on every
    // catalog change (mid-session membership shifts).
    const { syncBackendOperationInverses } = await import('./shell-v0/substrates/effects/index.ts')
    syncBackendOperationInverses(listOperations())
    onCatalogChange(() => syncBackendOperationInverses(listOperations()))
  } catch {
    // Best-effort — palette still shows shell commands without operations.
  }

  // Tempdoc 507 §3.3 — register core surfaces through PluginRegistry.
  // CorePlugin manifest declares all core surfaces and routes their
  // lifecycle through the same registry third-party plugins use.
  try {
    const { getSessionPluginRegistry } = await import('./shell-v0/plugin-api/sessionRegistry.ts')
    const { createCorePluginManifest } = await import('./shell-v0/plugin-api/CorePlugin.ts')
    const registry = getSessionPluginRegistry()
    registry.setHostApiDeps({
      apiBase,
      registerSurfacePort: () => {},
    })
    // Tempdoc 508 §11.8 / §13.8 — fetch host contractVersions so the
    // registry validates plugin manifests declaring host.* sub-API
    // requirements. Best-effort: if /infra/capabilities is unavailable
    // the registry falls back to the legacy single-Category check.
    try {
      const res = await authorizedFetch(`${apiBase}/infra/capabilities`)
      if (res.ok) {
        const view = await res.json()
        const versions = view?.serverCapabilities?.contractVersions
        if (versions && typeof versions === 'object') {
          registry.setHostContractVersions(versions)
        }
      }
    } catch {
      // Best-effort — legacy check stays active without it.
    }
    if (!registry.has('core')) {
      // Tempdoc 560 §4.1: the compiled-in core plugin is CORE-tier. Stamp it so the trust badge (and
      // any tier-gated constraint) reflects the real tier, not the signature-presence default
      // (unsigned → would mislabel core as untrusted).
      registry.install(createCorePluginManifest(), 'CORE')
    }
    // Tempdoc 560 §25/§26 — the Token Editor is the ONE token editor, shipped as a bundled
    // first-party plugin installed at boot (TRUSTED_PLUGIN — trusted by construction; no dev server,
    // no signing). Isolated try/catch: a failure logs but never blocks CorePlugin or discovery.
    // Unconditional (NOT import.meta.env.DEV-gated) so it ships in production.
    try {
      const { createTokenEditorPluginManifest } = await import(
        './shell-v0/plugins/token-editor/TokenEditorPlugin.ts'
      )
      if (!registry.has('token-editor')) {
        registry.install(createTokenEditorPluginManifest(), 'TRUSTED_PLUGIN')
      }
    } catch (err) {
      appLog.error('Token editor plugin install failed', {
        error: err instanceof Error ? err.message : String(err),
      })
    }
    // Tempdoc 507 §6 Phase 4 + 508 §12.1 — file-based plugin
    // distribution. After CorePlugin lands, scan ~/.justsearch/plugins/
    // and install everything discovered through the same lifecycle
    // third-party URL-loaded plugins use. Browser dev mode no-ops here
    // (PluginSourceProvider.discoverPlugins returns []).
    try {
      const { discoverPlugins } = await import('./shell-v0/plugin-api/PluginSourceProvider.ts')
      const { loadPluginFromUrl } = await import('./shell-v0/plugin-api/PluginLoader.ts')
      const discovered = await discoverPlugins()
      for (const plugin of discovered) {
        if (registry.has(plugin.id)) continue
        // §13 critical-analysis A4: skip oversized plugins (Rust
        // returned a tooLarge flag because the file exceeded the
        // 64 KB manifest / 1 MB source caps).
        if (plugin.tooLarge) {
          appLog.error('Plugin skipped — exceeded size cap', {
            pluginId: plugin.id,
            path: plugin.path,
          })
          continue
        }
        try {
          await loadPluginFromUrl(
            registry,
            `data:text/javascript,${encodeURIComponent(plugin.sourceText)}`,
            // Tempdoc 560 Phase 3 / 547 F4 — pass the discovered id so the UNTRUSTED bundle's
            // localStorage / customElements / document namespaces are scoped per-plugin instead
            // of sharing the 'unknown' default (under which every discovered plugin could read
            // each other's storage + collide on element tags), and the loader verifies the
            // evaluated manifest id matches what discovery claimed.
            { expectedPluginId: plugin.id },
          )
        } catch (loadErr) {
          appLog.error('Discovered plugin failed to install', {
            pluginId: plugin.id,
            error: loadErr instanceof Error ? loadErr.message : String(loadErr),
          })
        }
      }
    } catch (err) {
      appLog.error('Plugin discovery failed', { error: err instanceof Error ? err.message : String(err) })
    }
  } catch (err) {
    appLog.error('CorePlugin registration failed', { error: err instanceof Error ? err.message : String(err) })
  }

  // Tempdoc 508 §6 — hot-reload watcher for plugin directory (Tauri only).
  try {
    const { getPluginDirectory, getThemeDirectory } = await import('./shell-v0/plugin-api/PluginSourceProvider.ts')
    const { startHotReload } = await import('./shell-v0/plugin-api/PluginHotReload.ts')
    const { getSessionPluginRegistry } = await import('./shell-v0/plugin-api/sessionRegistry.ts')
    const pluginDir = await getPluginDirectory()
    const themeDir = await getThemeDirectory()
    if (pluginDir) {
      const registry = getSessionPluginRegistry()
      await startHotReload({
        pluginDir,
        themeDir: themeDir ?? undefined,
        onPluginReload: async () => {
          // File change detected. For each installed plugin, re-read its
          // source from disk and reload through the existing lifecycle.
          const { readPluginSource } = await import('./shell-v0/plugin-api/PluginSourceProvider.ts')
          const { loadPluginFromUrl } = await import('./shell-v0/plugin-api/PluginLoader.ts')
          for (const installed of registry.list()) {
            if (installed.manifest.id === 'core') continue
            try {
              const src = await readPluginSource(`${pluginDir}/${installed.manifest.id}`)
              if (src) {
                registry.uninstall(installed.manifest.id)
                await loadPluginFromUrl(
                  registry,
                  `data:text/javascript,${encodeURIComponent(src)}`,
                  // Tempdoc 560 Phase 3 / 547 F4 — keep the per-plugin namespace on reload too.
                  { expectedPluginId: installed.manifest.id },
                )
              }
            } catch (err) {
              appLog.error('Plugin hot-reload failed', {
                pluginId: installed.manifest.id,
                error: err instanceof Error ? err.message : String(err),
              })
            }
          }
        },
        onThemeReload: async () => {
          // Theme file changed — re-apply the active palette through the one
          // appearance writer (C1), so even the dev hot-reload path has no
          // separate theme-DOM writer.
          try {
            const { applyAppearance, getActiveThemeId } = await import('./shell-v0/state/themeState.ts')
            const id = getActiveThemeId()
            if (id) await applyAppearance({ paletteId: id })
          } catch (err) {
            appLog.error('Theme hot-reload failed', { error: err instanceof Error ? err.message : String(err) })
          }
        },
        registry,
        onError: (err) => {
          appLog.error('Hot-reload watcher error', { error: err.message })
        },
      })
    }
  } catch (err) {
    appLog.error('Hot-reload startup failed', {
      error: err instanceof Error ? err.message : String(err),
    })
  }

  // Tempdoc 507 §6 Phase 5 — initialize layout catalog with built-in layouts.
  try {
    const { initLayoutCatalog } = await import('./shell-v0/layout/LayoutManifest.ts')
    initLayoutCatalog()
  } catch (err) {
    appLog.error('Layout catalog init failed', { error: err instanceof Error ? err.message : String(err) })
  }

  // Tempdoc 508 §11.9 polish — register the reusable <jf-sparkline>
  // custom element. Plugins and future pin-renderers can use it
  // without re-importing the module.
  try {
    await import('./shell-v0/components/Sparkline.ts')
  } catch (err) {
    appLog.error('Sparkline registration failed', { error: err instanceof Error ? err.message : String(err) })
  }

  // Tempdoc 508 §11.5 / §13.5 — virtual-operations bridge.
  try {
    const {
      bootVirtualOperationCatalog,
      setVirtualOperationPublisher,
      serializeVirtualOperationsForAgent,
      publishNow,
    } = await import('./shell-v0/commands/VirtualOperationCatalog.ts')
    bootVirtualOperationCatalog()
    setVirtualOperationPublisher(async () => {
      try {
        const tools = serializeVirtualOperationsForAgent()
        await authorizedFetch(`${apiBase}/api/chat/agent/virtual-operations`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ tools }),
        })
      } catch {
        // best-effort; backend may not be ready yet at boot
      }
    })
    // Tempdoc 508-followup §β2 — await the initial publish before the
    // shell renders. Without this, a user invoking the agent immediately
    // after page load could see a stale tool list because the bootVirtual
    // publish ran fire-and-forget.
    await publishNow()
  } catch (err) {
    appLog.error('VirtualOperationCatalog boot failed', {
      error: err instanceof Error ? err.message : String(err),
    })
  }

  // Tempdoc 508-followup §β4 — profile-switch invalidation. Wire the
  // four consumers that don't otherwise reset on activeProfileId change
  // (KeybindingRegistry source='user' entries, search filters, search
  // state, inspector state). Each handler is fire-and-forget; the
  // UserStateDocument switch itself is atomic.
  try {
    const { subscribeProfileSwitch } = await import('./shell-v0/state/UserStateDocument.ts')
    const { rebindUserKeybindings } = await import('./shell-v0/commands/KeybindingRegistry.ts')
    const { clearFilters } = await import('./shell-v0/state/searchFiltersState.ts')
    const { resetSearchState } = await import('./shell-v0/state/searchState.ts')
    const { resetInspectorState } = await import('./shell-v0/state/inspectorState.ts')
    subscribeProfileSwitch(() => {
      try { rebindUserKeybindings() } catch (e) {
        appLog.error('Keybinding rebind on profile switch failed', { error: e instanceof Error ? e.message : String(e) })
      }
      try { clearFilters() } catch (e) {
        appLog.error('Search filter clear on profile switch failed', { error: e instanceof Error ? e.message : String(e) })
      }
      try { resetSearchState() } catch (e) {
        appLog.error('Search state reset on profile switch failed', { error: e instanceof Error ? e.message : String(e) })
      }
      try { resetInspectorState() } catch (e) {
        appLog.error('Inspector reset on profile switch failed', { error: e instanceof Error ? e.message : String(e) })
      }
    })
  } catch (err) {
    appLog.error('Profile-switch invalidation wiring failed', {
      error: err instanceof Error ? err.message : String(err),
    })
  }

  // Slice 477 H3.3 — load the theme manifest BEFORE restoring active
  // theme, so the picker can resolve user-added themes at restore time.
  try {
    const { fetchThemeManifest } = await import('./shell-v0/themes/themeManifest.ts')
    const manifest = await fetchThemeManifest()
    if (manifest && manifest.themes.length > 0) {
      const { setActiveCatalog } = await import('./shell-v0/themes/themesCatalog.ts')
      setActiveCatalog(manifest.themes.map((t) => ({
        id: t.id,
        displayName: t.displayName,
        description: t.description ?? '',
        cssPath: t.cssPath,
        // Tempdoc 855 §15.3 — thread the manifest's declared swatch through to the catalog entry
        // the settings theme-picker grid reads (undefined when the manifest entry omits it).
        ...(t.swatch !== undefined ? { swatch: t.swatch } : {}),
      })))
    }
  } catch {
    // ignore — manifest load is best-effort; built-in catalog is the fallback
  }

  // Slice 474 V1.5.1 polish — restore the persisted active theme so
  // user customization survives reloads. Best-effort; on failure the
  // chrome falls back to default tokens.
  try {
    const { restoreAppearanceOnBoot } = await import('./shell-v0/state/themeState.ts')
    // §2.C / C1 — ONE boot entry: restoreAppearanceOnBoot replays BOTH the
    // light/dark + high-contrast appearance AND the persisted palette through
    // the single appearance writer, so they survive a reload even if the
    // Settings surface never mounts.
    await restoreAppearanceOnBoot()
    // 569 §19 Seam 4 — re-project the persisted adaptation profile (motion) AFTER the appearance.
    // Density already threads via userConfig.
    const { restoreAdaptationProfileOnBoot, migrateLegacyContrastPreference } = await import(
      './shell-v0/state/adaptationProfile.ts'
    )
    restoreAdaptationProfileOnBoot()
    // Tempdoc 855 §17 R1 — contrast is no longer a profile axis; hand a pre-existing FE-local
    // preference to the canonical backend field ONCE. Runs here because both values are known and
    // restoreAppearanceOnBoot has already applied the canonical one (which this may overturn).
    // No-ops without a network call once migrated.
    await migrateLegacyContrastPreference()
  } catch {
    // ignore — theme restoration is best-effort
  }

  // 569 Fix 1 / §14 — apply a Presentation Declaration at boot so the real Settings
  // Interface+Appearance region AND the Library cards region render THROUGH the projection engine BY
  // DEFAULT (the inversion is the operating mode, not an opt-in). 569 §19 Seam 5 — restore the user's
  // PERSISTED active declaration if one resolves, else the built-in `CORE_DECLARED` default. Both go
  // through the same certify + degrade-never-fail apply path; the built-in Lit render is the
  // quarantine fallback.
  try {
    const { restoreActivePresentationOnBoot } = await import('./shell-v0/state/presentationState.ts')
    restoreActivePresentationOnBoot() // the ONE writer (theme + body/layout + interaction)
  } catch {
    // ignore — presentation boot is best-effort; the built-in render is the fallback
  }

  const root = document.getElementById('root')
  root.innerHTML = ''
  const shell = document.createElement('jf-shell')
  shell.setAttribute('api-base', apiBase)
  root.appendChild(shell)

  // Tempdoc 669 — demo/recording mode. Dismisses every currently-registered,
  // not-yet-dismissed walkthrough (the first-run "Welcome to JustSearch" tour
  // and any future one) so a real, non-headless browser session can be put
  // into a clean state for a screen recording without clicking through the
  // tour first. Must run AFTER root.appendChild(shell) above: <jf-shell>'s
  // connectedCallback is what registers the core walkthroughs
  // (installCoreWalkthroughManifest, chrome/Shell.ts), so listWalkthroughs()
  // is empty before this point. Not a Settings toggle — deliberately narrow,
  // opt-in tooling for recording, not a persistent user-facing preference.
  if (params.get('demoMode') === '1') {
    try {
      const { listWalkthroughs } = await import('./shell-v0/commands/WalkthroughRegistry.ts')
      const { dismissWalkthrough } = await import('./shell-v0/state/UserStateDocument.ts')
      for (const walkthrough of listWalkthroughs()) {
        dismissWalkthrough(walkthrough.id)
      }
    } catch {
      // ignore — demo-mode tour suppression is best-effort
    }
  }
}

bootstrap()
