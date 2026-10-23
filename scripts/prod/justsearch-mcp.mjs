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
 * A pinned port (--port, JUSTSEARCH_API_PORT) needs JUSTSEARCH_SESSION_TOKEN and is used only as
 * the Engine of a runtime manifest that names it, proven as in discovery; a pinned port that no
 * runtime manifest names is refused, so the token never goes to whatever answers it.
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
