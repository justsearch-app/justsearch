#!/usr/bin/env node
/**
 * Production MCP stdio server for JustSearch.
 *
 * Connects to a running JustSearch desktop instance and exposes search/indexing
 * tools to any MCP-compatible agent (Claude Code, Cursor, Windsurf, etc.).
 *
 * Usage:
 *   node justsearch-mcp.mjs [--port <port>] [--verbose]
 *
 * A pinned port (--port, JUSTSEARCH_API_PORT) that a runtime manifest names is that manifest's
 * Engine, proven as in discovery. A pinned port that no runtime manifest names is trusted with request content from whatever answers it, but never with a session token fetched on another connection.
 *
 * See docs/tempdocs/187-production-mcp-server.md for architecture and design.
 */

import { main } from './justsearch-mcp/server.mjs';

main().catch((err) => {
  try {
    process.stderr.write(`[justsearch-mcp] fatal: ${err?.stack || String(err)}\n`);
  } catch (_) {}
  process.exit(1);
});
