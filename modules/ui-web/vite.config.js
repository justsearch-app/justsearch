/// <reference types="vitest" />
import process from 'node:process'
import http from 'node:http'
import path from 'node:path'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import { visualizer } from 'rollup-plugin-visualizer'
import { findRunningManifest, isPidAlive } from '../../scripts/lib/platform-paths.mjs'

// ESM has no `__dirname`; derive it once for devExamplesMiddleware
// (path.resolve below) and resolveBackend (cwd / extraDataDirs). Vite and
// vitest inject a `__dirname` shim via esbuild when they load this config,
// but knip loads it through bare Node ESM `import()` where `__dirname` is
// undefined — so this explicit binding is what lets the knip dead-code gate
// actually run (without it, knip crashes loading this file and the gate
// passes vacuously on an empty report).
const __dirname = path.dirname(fileURLToPath(import.meta.url))

/**
 * Slice 471/474 — dev-only middleware that serves `/examples/<path>`
 * from `modules/ui-web/dev-examples/<path>`. Production builds do NOT
 * include the dev-examples directory (it lives outside Vite's `public/`).
 *
 * This avoids accidentally bundling unsigned plugin source into the
 * production Tauri binary while keeping the live-smoke + reference
 * examples accessible during dev.
 */
function devExamplesMiddleware() {
  return {
    name: 'jf-dev-examples-middleware',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (!req.url || !req.url.startsWith('/examples/')) {
          return next()
        }
        // Strip query (Vite adds ?import etc.) for filesystem lookup
        const [pathname] = req.url.split('?')
        const rel = pathname.replace(/^\/examples\//, '')
        // Disallow path traversal
        if (rel.includes('..')) {
          res.statusCode = 403
          res.end('forbidden')
          return
        }
        const abs = path.resolve(__dirname, 'dev-examples', rel)
        if (!fs.existsSync(abs) || !fs.statSync(abs).isFile()) {
          return next()
        }
        const ext = path.extname(abs).toLowerCase()
        const ct = ext === '.js' ? 'text/javascript; charset=utf-8'
          : ext === '.css' ? 'text/css; charset=utf-8'
          : ext === '.json' ? 'application/json; charset=utf-8'
          : 'text/plain; charset=utf-8'
        res.setHeader('Content-Type', ct)
        res.setHeader('Cache-Control', 'no-cache')
        res.end(fs.readFileSync(abs))
      })
    },
  }
}

// --- Backend discovery (tempdoc 501 Phase 5) ---
//
// Replaces the original 501-Phase-1 candidate-ladder for api-port.txt with a
// manifest-first lookup via scripts/lib/platform-paths.mjs::findRunningManifest.
// Cache key: instanceId (UUID minted by HeadlessApp at boot). When the producer
// restarts, the cached identity stops matching and the next call refreshes.
// When the producer crashes, isPidAlive returns false on the cached PID and we
// drop the cache.
//
// Env var VITE_JUSTSEARCH_API_PORT / VITE_API_PORT survives as a test-only
// override that short-circuits manifest discovery — useful when tests want to
// pin the proxy target without spawning a real backend.

let _cachedDiscovery = null

function envPortOverride() {
  const raw = process.env.VITE_JUSTSEARCH_API_PORT || process.env.VITE_API_PORT
  if (!raw) return null
  const p = parseInt(raw, 10)
  return Number.isFinite(p) && p > 0 && p <= 65535 ? p : null
}

function resolveBackend() {
  const override = envPortOverride()
  if (override) return { port: override, instanceId: null, source: 'env-override' }

  // Cache hit: same instanceId still alive.
  if (_cachedDiscovery) {
    if (isPidAlive(_cachedDiscovery.pid) === true) return _cachedDiscovery
    _cachedDiscovery = null
  }

  const found = findRunningManifest({
    cwd: __dirname,
    extraDataDirs: [path.join(__dirname, '.dev-data')],
  })
  if (!found) return null

  _cachedDiscovery = {
    port: found.manifest?.head?.apiPort ?? null,
    instanceId: found.manifest?.instanceId ?? null,
    pid: found.manifest?.pid ?? null,
    dataDir: found.dataDir,
    source: 'manifest',
  }
  return _cachedDiscovery
}

function resolveBackendPort() {
  const d = resolveBackend()
  return d ? d.port : null
}

const HOP_BY_HOP_HEADERS = new Set([
  'connection', 'keep-alive', 'transfer-encoding',
  'te', 'trailer', 'upgrade', 'proxy-authorization', 'proxy-authenticate',
])

function apiProxyPlugin() {
  // Tempdoc 501 Phase 21: the dev proxy intercepts /api AND the
  // /.well-known/justsearch/ namespace so the browser can reach the well-known
  // manifest mirror through Vite. Without the second mount, /.well-known/...
  // requests fall through to Vite's SPA fallback and silently return index.html
  // — which silently breaks any browser-side consumer that follows the RFC-8615
  // convention.
  const forwardPaths = ['/api', '/.well-known/justsearch']
  const makeProxyHandler = (rewritePrefix) => (req, res) => {
    const port = resolveBackendPort()
    if (!port) {
      res.writeHead(502, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({
        error: 'No running JustSearch backend discovered',
        hint: 'No live manifest found (env override, dev-data, or platform default). Is the backend running?',
      }))
      return
    }

    const proxyReq = http.request({
      hostname: '127.0.0.1',
      port,
      path: rewritePrefix + req.url,
      method: req.method,
      headers: { ...req.headers, host: `127.0.0.1:${port}` },
      // Tempdoc 545: per-request socket (no keep-alive pool) so a long-lived
      // SSE stream can't starve subsequent in-page fetches under CDP automation.
      agent: false,
    }, (proxyRes) => {
      const headers = Object.fromEntries(
        Object.entries(proxyRes.headers).filter(([k]) => !HOP_BY_HOP_HEADERS.has(k.toLowerCase()))
      )
      res.writeHead(proxyRes.statusCode, headers)
      proxyRes.pipe(res)
    })

    proxyReq.on('error', (err) => {
      if (!res.headersSent) {
        res.writeHead(502, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ error: `Backend unavailable: ${err.message}` }))
      }
    })

    req.pipe(proxyReq)
  }
  const mountProxy = (server) => {
    for (const p of forwardPaths) {
      server.middlewares.use(p, makeProxyHandler(p))
    }
  }
  return {
    name: 'jf-api-proxy',
    // Tempdoc 586 §F-1d — the proxy now also serves under `vite preview`, so the
    // PRODUCTION bundle (dist/) can reach the live backend for a prod-parity
    // cold-load measurement. No `apply` filter: the only hooks are dev/preview
    // server middleware, which simply don't fire during `vite build`.
    configureServer(server) {
      mountProxy(server)
    },
    configurePreviewServer(server) {
      mountProxy(server)
    },
  }
}

// --- end port discovery ---

const isTauri = process.env.TAURI_PLATFORM !== undefined
const isDebug = process.env.TAURI_DEBUG === 'true'
const isAnalyze = process.env.ANALYZE === 'true'

export default defineConfig(({ command, mode }) => {
  // Vitest also uses serve. Keep test endpoint selection controllable by stubEnv;
  // a discovered desktop port would otherwise become an unchangeable bundle literal.
  const discoveredPort = command === 'serve' && mode !== 'test' ? resolveBackendPort() : null

  return {
  // Slice 3a.1.4b §B.I Finding 2 fix: in dev mode (`command === 'serve'`), expose the
  // Vite-server's proxy target port to the bundle via `import.meta.env.VITE_JUSTSEARCH_API_PORT`.
  // Without this, `resolveApiEndpoint()` returns null in plain `npm run dev`, and the message
  // catalog boot's `if (endpoint.baseUrl)` short-circuits — leaving runtime catalogs unfetched
  // and labels showing raw keys. Production builds (`command === 'build'`) leave the var unset,
  // preserving the existing Tauri-/runtime-driven endpoint resolution.
  //
  // Tempdoc 501: reads from port file when env var is absent, so all launch paths
  // (bare `npm run dev`, dev-runner, dev:all) get a populated value.
  define: discoveredPort ? {
    'import.meta.env.VITE_JUSTSEARCH_API_PORT': JSON.stringify(String(discoveredPort)),
  } : {},
  plugins: [
    apiProxyPlugin(),
    devExamplesMiddleware(),
    isAnalyze && visualizer({
      filename: 'dist/bundle-stats.html',
      open: true,
      gzipSize: true,
      brotliSize: true,
    }),
  ].filter(Boolean),
  base: isTauri ? './' : '/',
  server: {
    // The backend's runtime data dir (`.dev-data/`) lives INSIDE the Vite root
    // (see resolveBackend's extraDataDirs above) and is gitignored — it holds a
    // live Lucene index, SQLite WAL files, worker logs, and telemetry ndjson that
    // the backend rewrites continuously while running. Without this ignore,
    // chokidar fires a full `page reload` on every such write, which wipes the
    // in-progress `bootstrap()` before <jf-shell> mounts — so the app
    // intermittently never appears under a running dev stack (observed
    // 2026-06-20). Vite appends this to its built-in ignores (.git/node_modules/
    // cacheDir), so src/** HMR is unaffected.
    watch: { ignored: ['**/.dev-data/**'] },
  },
  build: {
    target: ['es2022', 'safari13'],
    minify: !isDebug,
    sourcemap: isDebug,
    chunkSizeWarningLimit: 500,
    rollupOptions: {
      output: {
        manualChunks(id) {
          const normalizedId = id.replace(/\\/g, '/')
          if (!normalizedId.includes('/node_modules/')) return undefined

          if (normalizedId.includes('/node_modules/zod/')) return 'vendor-zod'
          // Slice 477 H2.6 — split SES + Endo into a vendor chunk.
          // Loaded lazily by PluginLoader's dynamic import('ses'); users who
          // never load a plugin don't pay the cost on cold start. NOTE: this
          // chunk is large (~1.46 MB — `ses` + all of `@endo/*`), far above the
          // per-chunk hard cap; that overage is a declared emergency-override
          // (gates/ui-bundle/.changesets/) — SES is a single irreducible
          // vendored runtime and is already off the initial critical path.
          if (
            normalizedId.includes('/node_modules/ses/') ||
            normalizedId.includes('/node_modules/@endo/')
          ) {
            return 'vendor-ses'
          }
          // Keep the Lumino widget framework (the core Shell's DockLayout /
          // DockPanel) out of the app entry chunk so `app_main` stays smaller.
          // Lumino is eagerly loaded by the shell, so it still counts toward
          // total_js_bytes, but isolating it shrinks index-*.js (~180 kB out).
          if (normalizedId.includes('/node_modules/@lumino/')) {
            return 'vendor-lumino'
          }
          return undefined
        },
      },
    },
  },
  test: {
    // Node-like environment for pure logic tests (no DOM needed initially)
    environment: 'node',
    // Keep globals off - import from vitest
    globals: false,
    // Test file patterns
    include: ['src/**/*.{test,spec}.{js,ts,tsx}'],
    // Exclude E2E tests (Playwright) and lockdown tests (separate
    // config; lockdown freezes Date, breaks happy-dom).
    exclude: ['e2e/**', 'node_modules/**', 'src/**/*-lockdown.test.{js,ts,tsx}'],
    // Tempdoc 521 §16.1 Phase D — silence the streamViaHost fallback
    // warning across the test suite. The warning is a production-time
    // signal that `host_` wasn't forwarded; test contexts deliberately
    // exercise the fallback path with mocks, so the warning is noise
    // there.
    setupFiles: ['./src/__test-setup__/streamViaHostSuppress.ts'],
  },
}})
