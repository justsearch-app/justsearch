# JustSearch MCPB bundle

An [MCPB (MCP Bundle)](https://github.com/anthropics/mcpb) that gives Claude Desktop
users a one-click install of JustSearch's MCP connection, and satisfies the
[Official MCP Registry](https://registry.modelcontextprotocol.io)'s packaging
requirement (an MCPB attached to a GitHub release).

## What it is (and is not)

JustSearch's real MCP server runs **in-process** inside the desktop app, as
Streamable HTTP on the loopback API port (`POST /mcp` — see
[`docs/reference/mcp-production-server.md`](../../docs/reference/mcp-production-server.md)).
MCPB hosts launch bundles over **stdio**, so this bundle is only a **thin
stdio→Streamable-HTTP bridge** (`server/index.js`):

1. On start it discovers the live API port: `JUSTSEARCH_API_PORT` env var →
   `runtime/manifest.json` in `JUSTSEARCH_DATA_DIR` when set → the same file in the
   desktop app's data folder (`%APPDATA%\io.justsearch.shell` on Windows;
   `$XDG_DATA_HOME/io.justsearch.shell`, or `~/.local/share/io.justsearch.shell`, on Linux).
   A manifest counts only if its pid is alive, the service on its `head.apiPort`, over a
   connection the operating system reports as that pid's (Linux `/proc`, Windows `netstat -ano`;
   see 3.), answers `GET /api/runtime/live` with the same pid and instance id, and
   `GET /api/health` answers 200 (a proven Engine still starting, 503, is re-checked for up to
   30 s, so starting the host and the app together works); there is no default-port guess. On Windows, when `%APPDATA%`
   is unset in its environment, it also asks Windows for the Roaming folder (waiting up to 30 s
   for that answer; a lookup that times out or fails is named in the not-found error). The bridge never
   looks in a dev checkout: when the installed app and a dev Engine both run, it uses the
   installed app; set `JUSTSEARCH_DATA_DIR` to the checkout's data folder (for example
   `<checkout>/modules/ui-web/.dev-data`) to use the dev Engine (a `JUSTSEARCH_API_PORT` pin
   alone is refused unless a manifest the bridge reads names that port; see 3.).
   (The production stdio server, `scripts/prod/justsearch-mcp.mjs`, tries more: `--port`,
   `JUSTSEARCH_API_PORT`, the legacy `JUSTSEARCH_HOME` folder, which wins over
   `JUSTSEARCH_DATA_DIR`, then `JUSTSEARCH_DATA_DIR`, the desktop app's folder, a dev
   checkout's `.dev-data` folders and the user data folder; the bridge has no `JUSTSEARCH_HOME`
   step.)
   The resolver (`server/engine-discovery.cjs`) is shared with the production MCP server and
   follows the `discovery.desktopAppDataDir` entry of `contracts/platform-paths/spec.v1.json`.
2. If no running JustSearch is found, it exits with an actionable message that
   names every location it tried (install + launch the desktop app).
3. After proving the Engine, it takes `head.sessionToken` from that validated owner-only
   runtime manifest and sends `X-JustSearch-Session` on requests, including the GET stream.
   Discovery probes remain unauthenticated. Every request to the Engine
   (public probes, each message's POST, the GET stream) goes through one function
   (`engineRequest` in `server/engine-discovery.cjs`) whose keep-alive connections carry
   nothing until the manifest still names the same Engine and the OS reports that pid as the
   owner of the server end of that very connection; a reused connection needs no new proof.
   If a proof fails, nothing is sent: the bridge drops the token and re-runs discovery, so a
   token never reaches whatever took the port. A `JUSTSEARCH_API_PORT` pin requires
   `JUSTSEARCH_SESSION_TOKEN` (the bridge fails with a message naming the variable if it is
   absent), and that token goes only to the Engine of a runtime manifest that names the pinned
   port, with the same proof; if that proof fails, the pin is refused. A stale manifest (its pid
   is gone) that names the pinned port is a refusal, and so is a pin that no manifest names:
   nothing proves what answers it, so it never gets the token. No token HTTP endpoint or stdout
   line is used.
4. Otherwise it forwards newline-delimited JSON-RPC from stdin to
   `http://127.0.0.1:<port>/mcp` (JSON and SSE response bodies both handled)
   and relays server messages back on stdout. If the app restarts on a
   different port mid-session, or another Engine answers the pinned port, the
   bridge re-runs that discovery, drops everything negotiated with the old Engine
   (the `Mcp-Session-Id`, which is only ever sent to the Engine that issued it, the
   `MCP-Protocol-Version` and the server stream; also when it assigned no session id), and re-initializes the session with
   the cached `initialize` params, sent exactly as a first `initialize` is; the
   version is negotiated again from the new Engine's answer (an answer or event that still
   arrives from the old session changes nothing). Until a session's own `initialize` has been
   answered (bounded at 10 s), no other message is sent in it: calls wait for it, or for the
   session that replaces it, and a call before any `initialize` is refused unsent. The failed call is sent again once only if it surely did not reach the
   app (it never had a connection, or the app answered 400/401/404 for it);
   otherwise it fails with an error saying its outcome is unknown, so a mutating
   call never runs twice. Idle connections are closed after 25 s, before the
   app's 30 s idle expiry. Closing stdin stops the bridge, also during discovery.

The bridge is **dependency-free** (Node builtins only) and never touches the
network beyond `127.0.0.1` — no npx fetches, no downloads, ever.

The desktop app indexes and runs built-in inference locally. It checks for
updates on launch when its build embeds a release URL, including CI-built branch
candidates and pre-releases; there is no off switch yet. Install AI downloads
models and runtimes from `github.com` and `huggingface.co` with consent.
Trace export is off by default; enabling tracing and setting an OTLP endpoint
sends traces there, potentially including paths and document excerpts.
The bridge returns retrieved passages and paths to the MCP client you connect;
a cloud-hosted client can forward them to its model provider. See
[Privacy](../../README.md#privacy) for the network paths.

The bundle does **not** contain JustSearch itself. The desktop app
(Windows-only) must be installed and running:
<https://github.com/justsearch-app/justsearch/releases>.

## Files

| File | Purpose |
|---|---|
| `manifest.json` | MCPB manifest (schema v0.4) — metadata, the 6 tools, `win32`-only compatibility |
| `server/index.js` | The stdio→HTTP bridge (zero dependencies) |
| `server/engine-discovery.cjs` | Desktop data folder + validated manifest selection (shared with `scripts/prod/justsearch-mcp`) |
| `server.json` | Official MCP Registry metadata (registry schema `2025-12-11`) — **not yet published** |
| `test/smoke.mjs` | Scripted stdio MCP client: success path (initialize → tools/list → `justsearch_status`) and failure path (`--expect-unreachable`) |
| `.mcpbignore` | Keeps the packed bundle to `manifest.json` + `server/` |
| `dist/` | Build output (gitignored) |

## Build

The bundle is built **deterministically from source** by `scripts/ci/pack-mcpb.mjs`
(zero-dependency Node; a STORED zip with a fixed mtime, so the same source always
produces the same bytes). It is **not committed** — the release build produces it, and
`server.json.fileSha256` is the committed record of its hash.

```powershell
# from the repo root; requires Node (already used by the build)
node scripts/ci/pack-mcpb.mjs packaging/mcpb/dist/justsearch-mcp.mcpb   # write the bundle
node scripts/ci/pack-mcpb.mjs --sync                                    # write its hash into server.json
```

After editing `manifest.json` or `server/**`, run `--sync` and commit `server.json`.
The `check-mcpb-consistency` gate re-packs from source and fails the build if
`server.json.fileSha256` is stale — there is no way to ship a source/hash mismatch.

Signing: `mcpb sign` exists (code-signing certificate / self-signed). We do
not sign yet — same posture as the unsigned installer; revisit together with
installer code signing.

## Test

```powershell
# Failure path (JustSearch NOT running) — expects actionable error + exit 1:
node packaging/mcpb/test/smoke.mjs --expect-unreachable

# Success path (JustSearch running):
node packaging/mcpb/test/smoke.mjs
```

Local one-click check: drag `dist/justsearch-mcp.mcpb` onto
Claude Desktop → Settings → Extensions.

## Release flow (operator)

The bundle is **built from source** at release time (never committed); its hash is recorded
in `server.json.fileSha256` and enforced by the `check-mcpb-consistency` gate (rebuild +
compare) — the build fails on any source/hash drift, so there is no hand-editing of checksums
at release time. The pipeline (`scripts/ci/build-release-assets.ps1`, driven by
`build-installer.yml` / `package-installer-win.ps1 -AssembleReleaseAssets`) builds the bundle
and stages the installer + `justsearch-mcp.mcpb` + a generated `SHA256SUMS`, attaching all
three to the GitHub Release.

Operator steps:

1. **Only when the bundle source changed** — run `node scripts/ci/pack-mcpb.mjs --sync` and
   commit `server.json` (its `fileSha256` now matches the source).
   `node scripts/ci/check-mcpb-consistency.mjs` must pass. (The `mcpb-repack-hint` fires on
   source edits as a reminder.)
2. **Before a release cut** — bump `gradle.properties` to the release version; running
   `scripts/ci/sync-version.ps1` (also invoked by `package-installer-win.ps1`) then stamps
   `server.json`'s `version` and the `packages[0].identifier` release-asset URL to the same
   `v<version>` tag automatically via `pack-mcpb.mjs --set-version` (the URL must contain
   `mcp` — the filename satisfies it). `node scripts/ci/check-mcpb-consistency.mjs
   --release-version <version>` must pass.
3. **Cut the release** — the build stages + attaches the asset set automatically. Attach only
   to a release whose installer actually ships the `/mcp` endpoint: the v0.1.0 app does **not**
   (its backend predates the MCP handler), so the bundle is useful only from the next release
   onward.

## Registry publish (operator — do not run without approval)

Prereqs: the release asset above is live; the repo README contains the
ownership marker `<!-- mcp-name: io.github.justsearch-app/justsearch -->`
(added on this branch); you can complete a GitHub device login as
`eliasjustus` (GitHub auth is what authorizes the `io.github.eliasjustus/*`
namespace).

```powershell
# 1. Install the publisher CLI (see modelcontextprotocol.io/registry/quickstart)
# 2. Authenticate:
mcp-publisher login github
# 3. From packaging/mcpb/ (where server.json lives):
mcp-publisher publish
# 4. Verify:
curl "https://registry.modelcontextprotocol.io/v0.1/servers?search=io.github.justsearch-app/justsearch"
```

Note the registry is in preview (breaking changes / data resets possible).
MCP clients validate `fileSha256` before installing — a wrong hash breaks
installs even though the registry itself does not check it.

MCPB 0.3.1 works with the previous Engine release and the new file-based credential boundary.
Older bundles fail with HTTP 401 against the new Engine; install the updated bundle.
