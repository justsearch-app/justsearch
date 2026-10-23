/**
 * Production MCP server for JustSearch.
 *
 * Connects to a running JustSearch desktop instance and exposes search/indexing
 * tools to any MCP-compatible agent (Claude Code, Cursor, Windsurf, etc.).
 *
 * Adapted from scripts/dev/justsearch-dev-mcp/server.mjs with dev-runner
 * specifics removed (runId, NDJSON logging, process lifecycle, evidence capture).
 */


import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';

import { discover, pinnedEngine } from './discovery.mjs';
import { engineRequest } from '../../../packaging/mcpb/server/engine-discovery.cjs';
import {
  AnswerInputSchema,
  AnswerOutputSchema,
  SearchInputSchema,
  SearchOutputSchema,
  IngestInputSchema,
  IngestOutputSchema,
  OperationOutcomeInputSchema,
  ToolErrorSchema,
  StatusInputSchema,
  StatusOutputSchema,
} from './schemas.mjs';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

let verboseMode = false;

function log(msg) {
  if (verboseMode) process.stderr.write(`[justsearch-mcp] ${msg}\n`);
}

/**
 * Validate a URL is loopback-only.
 * Copied from scripts/dev/justsearch-dev-mcp/paths.mjs:63-83.
 */
function ensureLoopbackUrl(urlStr, label = 'url') {
  let u;
  try {
    u = new URL(String(urlStr));
  } catch {
    throw new Error(`${label} must be a valid URL: ${urlStr}`);
  }

  if (u.protocol !== 'http:') {
    throw new Error(`${label} must use http: (loopback-only). got=${u.protocol}`);
  }
  if (u.username || u.password) {
    throw new Error(`${label} must not include credentials`);
  }

  const host = String(u.hostname || '').toLowerCase();
  if (!(host === '127.0.0.1' || host === 'localhost' || host === '::1')) {
    throw new Error(`${label} must be loopback-only. host=${u.hostname}`);
  }
  return u;
}

/**
 * The Engine discovery selected: { port, engine, token, pin }, engine being the proven manifest
 * candidate, token its session token (from that validated manifest, or JUSTSEARCH_SESSION_TOKEN
 * for a pin; '' is dev mode), pin the operator's pin ('--port <n>' / 'JUSTSEARCH_API_PORT=<n>')
 * when there is one. Every request goes through engine-discovery.cjs engineRequest() with that
 * target's token, which writes nothing on a new connection until the OS says its server end
 * belongs to the manifest pid. A refusal comes back as error.message 'engine_gone', which
 * parseJsonResponse turns into ENGINE_GONE. No handler, and no follow-up request inside one tool
 * call, can reach an endpoint that is not that Engine with the session token.
 */
let engineTarget = null;
/** Discovery as at startup (set by main()); a pinned target is re-selected only on its pin. */
let rediscover = null;

function transportFailure(err) {
  return err?.code === 'ENGINE_NOT_PROVEN'
    ? { message: 'engine_gone', detail: `${err.status}: ${err.detail}` }
    : { message: err?.message || String(err), code: err?.code };
}

/** A failure known to precede execution: refused before writing, no connection, or the token refused. */
function refusedBeforeExecution(res) {
  if (res.error?.message === 'engine_gone' || res.error?.code === 'ECONNREFUSED') return true;
  if (res.statusCode !== 401) return false;
  try {
    return JSON.parse(res.text).errorCode === 'UI_TOKEN_REQUIRED';
  } catch {
    return false; // not a token refusal
  }
}

/**
 * Run one request (run(target) → the http*Once result). When it failed in a way known to precede
 * execution, the Engine is selected again (reconnect) and the request, which was never run, is
 * sent once more to the Engine now proven, with that Engine's token. Anything else is returned
 * as it is, so nothing that may have reached an Engine is sent twice.
 */
async function withReconnect(run) {
  const target = engineTarget;
  const res = await run(target);
  if (!refusedBeforeExecution(res)) return res;
  const next = await reconnect(target);
  return next ? run(next) : res;
}

let reconnecting = null;

/**
 * The target that replaces stale (one selection at a time; concurrent callers share it): for a
 * pin, the manifest Engine proven on that port now, with no fallback to any other Engine;
 * otherwise discovery as at startup. null when there is none.
 */
function reconnect(stale) {
  if (engineTarget !== stale) return Promise.resolve(engineTarget);
  if (!reconnecting) {
    const attempts = [];
    const found = stale.pin ? pinnedEngine(stale.port, stale.pin, attempts) : rediscover();
    reconnecting = found
      .then((backend) => {
        if (!backend) {
          log(`${stale.pin}: no proven Engine on the pinned port (${attempts.join('; ')})`);
          return null;
        }
        if (engineTarget === stale) engineTarget = targetOf(backend);
        return engineTarget;
      })
      .catch((err) => {
        log(`no proven Engine to reconnect to: ${err.message}`);
        return null;
      })
      .finally(() => {
        reconnecting = null;
      });
  }
  return reconnecting;
}

function targetOf(backend) {
  ensureLoopbackUrl(backend.baseUrl, 'baseUrl');
  return { port: backend.port, engine: backend.engine, token: backend.token, pin: backend.pin || null };
}

function engineGoneError(res) {
  const pin = engineTarget?.pin ? `, pinned by ${engineTarget.pin}` : '';
  return makeError(
    'ENGINE_GONE',
    `The JustSearch Engine this server connected to (pid ${engineTarget?.engine?.pid}, port ${engineTarget?.port}${pin}) ` +
      `is no longer the one serving that port (${res.error.detail}), and no proven Engine was found to take over. ` +
      'Nothing was sent. Start JustSearch, or restart the MCP server.',
  );
}

/**
 * HTTP GET with size limiting and timeout.
 * Adapted from scripts/dev/justsearch-dev-mcp/server.mjs:96-161.
 */
function httpGetTextLimited(urlStr, options) {
  return withReconnect((target) => httpGetOnce(target, urlStr, options));
}

function httpGetOnce(target, urlStr, { timeoutMs, maxBytes, headers = {} }) {
  return new Promise((resolve) => {
    let u;
    try {
      u = new URL(urlStr);
    } catch {
      return resolve({ ok: false, statusCode: null, text: null, error: { message: 'invalid_url' } });
    }

    let settled = false;
    const finish = (payload) => {
      if (settled) return;
      settled = true;
      resolve(payload);
    };

    const req = engineRequest(
      target,
      {
        path: u.pathname + u.search,
        timeoutMs,
        headers: { Accept: 'application/json', 'User-Agent': 'justsearch-mcp/0.1.0', ...headers },
        credential: true,
      },
      (res) => {
        const statusCode = typeof res.statusCode === 'number' ? res.statusCode : null;
        let bytes = 0;
        const chunks = [];
        let aborted = false;

        res.on('data', (chunk) => {
          if (aborted) return;
          bytes += chunk.length;
          if (bytes > maxBytes) {
            aborted = true;
            res.destroy(new Error('response_too_large'));
            return;
          }
          chunks.push(chunk);
        });
        res.on('error', (err) => {
          const text = Buffer.concat(chunks).toString('utf8');
          finish({
            ok: false,
            statusCode,
            text,
            error: { message: err?.message || String(err) },
          });
        });
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          finish({ ok: true, statusCode, text, error: null });
        });
      },
    );

    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', (err) => finish({ ok: false, statusCode: null, text: null, error: transportFailure(err) }));
  });
}

/**
 * HTTP POST with JSON body, size limiting, and timeout.
 * Adapted from scripts/dev/justsearch-dev-mcp/server.mjs:164-237.
 */
function httpPostJsonLimited(urlStr, body, options) {
  return withReconnect((target) => httpPostOnce(target, urlStr, body, options));
}

function httpPostOnce(target, urlStr, body, { timeoutMs, maxBytes, headers = {} }) {
  return new Promise((resolve) => {
    let u;
    try {
      u = new URL(urlStr);
    } catch {
      return resolve({ ok: false, statusCode: null, text: null, error: { message: 'invalid_url' } });
    }

    const bodyStr = JSON.stringify(body);

    let settled = false;
    const finish = (payload) => {
      if (settled) return;
      settled = true;
      resolve(payload);
    };

    const req = engineRequest(
      target,
      {
        method: 'POST',
        path: u.pathname + u.search,
        timeoutMs,
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(bodyStr),
          Accept: 'application/json',
          'User-Agent': 'justsearch-mcp/0.1.0',
          ...headers,
        },
        body: bodyStr,
        credential: true,
      },
      (res) => {
        const statusCode = typeof res.statusCode === 'number' ? res.statusCode : null;
        let bytes = 0;
        const chunks = [];
        let aborted = false;

        res.on('data', (chunk) => {
          if (aborted) return;
          bytes += chunk.length;
          if (bytes > maxBytes) {
            aborted = true;
            res.destroy(new Error('response_too_large'));
            return;
          }
          chunks.push(chunk);
        });
        res.on('error', (err) => {
          const text = Buffer.concat(chunks).toString('utf8');
          finish({
            ok: false,
            statusCode,
            text,
            error: { message: err?.message || String(err) },
          });
        });
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          finish({ ok: true, statusCode, text, error: null });
        });
      },
    );

    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', (err) => finish({ ok: false, statusCode: null, text: null, error: transportFailure(err) }));
  });
}

/**
 * Wrap structured content into the MCP tool result format.
 * Copied from scripts/dev/justsearch-dev-mcp/server.mjs:268-278.
 */
function toToolResult(structuredContent) {
  return {
    content: [
      {
        type: 'text',
        text: JSON.stringify(structuredContent, null, 2),
      },
    ],
    structuredContent,
  };
}

/**
 * Rename entity facet keys from internal Lucene field names (_raw suffix)
 * to agent-facing filter parameter names (no suffix). This ensures facet
 * keys like `entity_persons` match the filter parameter `entity_persons`,
 * so agents can copy facet keys directly into filter objects.
 */
function normalizeEntityFacetKeys(facets) {
  if (!facets) return facets;
  const renamed = {};
  for (const [key, value] of Object.entries(facets)) {
    const normalized = key.replace(/_raw$/, '');
    renamed[normalized] = value;
  }
  return renamed;
}

/**
 * Render match-span highlights as inline **bold** markers in excerpt text.
 * matchSpans have startChar/endChar relative to the excerpt text string.
 * Agents consume plain text, so bold markers are more useful than offsets.
 */
function renderExcerptHighlights(text, matchSpans) {
  if (!text || !matchSpans?.length) return text ?? '';
  // Sort ascending by start, then merge overlapping spans to avoid garbled markers
  const valid = matchSpans
    .filter((s) => s.startChar != null && s.endChar != null && s.startChar < s.endChar)
    .map((s) => [Math.max(0, s.startChar), Math.min(text.length, s.endChar)])
    .sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const merged = [];
  for (const [s, e] of valid) {
    if (merged.length > 0 && s <= merged[merged.length - 1][1]) {
      merged[merged.length - 1][1] = Math.max(merged[merged.length - 1][1], e);
    } else {
      merged.push([s, e]);
    }
  }
  // Insert markers in reverse order so offsets stay valid
  let result = text;
  for (let i = merged.length - 1; i >= 0; i--) {
    const [s, e] = merged[i];
    result = result.slice(0, s) + '**' + result.slice(s, e) + '**' + result.slice(e);
  }
  return result;
}

/**
 * Prune a search result to essential fields for token efficiency.
 * Copied from scripts/dev/justsearch-dev-mcp/server.mjs:803-816.
 */
function slimSearchResult(r, includeProvenance = false) {
  const f = r.fields || {};
  // Fall back to chunk_content when content_preview is empty (chunk results)
  const preview = f.content_preview || f.chunk_content || '';
  const slim = {
    score: r.score,
    filename: f.filename,
    path: f.path,
    file_kind: f.file_kind,
    size_bytes: Number(f.size_bytes) || 0,
    language: f.language,
    content_preview: preview.slice(0, 200),
    meta_source: f.meta_source || undefined,
    meta_author: f.meta_author || undefined,
    meta_category: f.meta_category || undefined,
    matchedFields: r.matchedFields,
  };
  if (includeProvenance && r.provenance) {
    slim.provenance = r.provenance;
  }
  // Include query-biased excerpts when present (include_excerpts=true)
  if (r.excerptRegions?.length) {
    slim.excerpts = r.excerptRegions.map((e) => ({
      text: renderExcerptHighlights(e.text, e.matchSpans),
      approx_line: e.approxLine ?? e.approx_line,
    }));
  }
  return slim;
}

function makeError(code, message) {
  return toToolResult({ ok: false, error: { message, code } });
}

function parseJsonResponse(res, { preserveOperationError = false } = {}) {
  if (res.error?.message === 'engine_gone') return { parsed: null, error: engineGoneError(res) };
  // Size-limit abort — check first, regardless of HTTP status code.
  // When the response body exceeds maxBytes, res.destroy() fires res.on('error')
  // which passes through the real HTTP statusCode (e.g. 200), so this check must
  // precede the statusCode == null branch.
  if (res.error?.message === 'response_too_large') {
    return { parsed: null, error: makeError('RESPONSE_TOO_LARGE', 'Response exceeded size limit. Try reducing the limit parameter.') };
  }
  // Connection-level failure (no HTTP response at all)
  if (!res.ok && res.statusCode == null) {
    return { parsed: null, error: makeError('NOT_CONNECTED', 'JustSearch is not running or unreachable. Ensure the desktop app is open.') };
  }
  if (preserveOperationError && res.statusCode !== 200) {
    let operationResponse;
    try { operationResponse = JSON.parse(res.text || ''); } catch { /* use the transport error below */ }
    if (operationResponse && typeof operationResponse === 'object' && !Array.isArray(operationResponse)) {
      const code = typeof operationResponse.errorCode === 'string' ? operationResponse.errorCode : 'HTTP_ERROR';
      const message = typeof operationResponse.error === 'string' ? operationResponse.error
        : typeof operationResponse.message === 'string' ? operationResponse.message : `HTTP ${res.statusCode}`;
      return { parsed: null, error: toToolResult({
        ok: false, statusCode: res.statusCode, operationResponse, error: { code, message },
      }) };
    }
  }
  // 401 — token rejected (UI_TOKEN_REQUIRED)
  if (res.statusCode === 401) {
    let errorCode = '';
    try {
      errorCode = JSON.parse(res.text || '').errorCode || '';
    } catch {}
    return {
      parsed: null,
      error: makeError(
        'TOKEN_REJECTED',
        `Session token was rejected${errorCode ? ` (${errorCode})` : ''}. ` +
          'The token may have expired. Restart the MCP server to obtain a new token.',
      ),
    };
  }
  // 400 — validation error (missing query, invalid cursor, etc.)
  if (res.statusCode === 400) {
    let detail = '';
    try {
      const body = JSON.parse(res.text || '');
      detail = body.error || body.message || '';
    } catch {}
    return {
      parsed: null,
      error: makeError('VALIDATION_ERROR', detail || 'Bad request — check tool arguments.'),
    };
  }
  // 503 — backend not ready
  if (res.statusCode === 503) {
    let state = '';
    try {
      state = JSON.parse(res.text || '').state || '';
    } catch {}
    return {
      parsed: null,
      error: makeError('NOT_READY', `Knowledge Server is not ready${state ? ` (state: ${state})` : ''}. Wait 30 seconds and retry.`),
    };
  }
  // Other HTTP errors
  if (!res.ok || res.statusCode !== 200) {
    return {
      parsed: null,
      error: makeError('HTTP_ERROR', (res.error?.message || `HTTP ${res.statusCode}`) + '. Try again.'),
    };
  }
  // Parse JSON
  try {
    return { parsed: JSON.parse(res.text || ''), error: null };
  } catch {
    return { parsed: null, error: makeError('INVALID_RESPONSE', 'Invalid JSON response from backend.') };
  }
}

// ---------------------------------------------------------------------------
// CLI arg parsing
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  let port = undefined;
  let verbose = false;
  for (let i = 2; i < argv.length; i++) {
    if (argv[i] === '--verbose' || argv[i] === '-v') {
      verbose = true;
    } else if (argv[i] === '--port' && i + 1 < argv.length) {
      port = parseInt(argv[++i], 10);
      if (!Number.isFinite(port) || port <= 0) {
        throw new Error(`Invalid --port value: ${argv[i]}`);
      }
    } else if (argv[i].startsWith('--port=')) {
      port = parseInt(argv[i].split('=')[1], 10);
      if (!Number.isFinite(port) || port <= 0) {
        throw new Error(`Invalid --port value: ${argv[i].split('=')[1]}`);
      }
    } else if (argv[i].startsWith('--') || argv[i].startsWith('-')) {
      process.stderr.write(`[justsearch-mcp] warning: unknown flag "${argv[i]}" ignored\n`);
    }
  }
  return { port, verbose };
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

export async function main() {
  const args = parseArgs(process.argv);
  verboseMode = args.verbose;

  log('Starting JustSearch production MCP server...');

  // Closing stdin stops startup too: read it during discovery, keeping what arrives for the
  // stdio transport, which takes over the stream once the backend is found.
  const early = [];
  const keepEarly = (chunk) => early.push(chunk);
  const endEarly = () => {
    log('stdin closed during startup — exiting');
    process.exit(0);
  };
  process.stdin.on('data', keepEarly);
  process.stdin.on('end', endEarly);

  // Discover backend
  rediscover = () => discover({ port: args.port, verbose: args.verbose });
  const backend = await rediscover();
  process.stdin.off('data', keepEarly);
  process.stdin.off('end', endEarly);
  process.stdin.pause();
  for (const chunk of early.reverse()) process.stdin.unshift(chunk);
  engineTarget = targetOf(backend);
  const { baseUrl, token } = backend;
  log(`Backend: ${baseUrl}`);
  // token (from the validated manifest, or JUSTSEARCH_SESSION_TOKEN for a pin) is sent on every
  // request to the proven Engine; "" is dev mode, with no token enforcement.
  log(`Token: ${token ? 'available (prod mode)' : 'empty (dev mode)'}`);

  // Create MCP server
  const mcpServer = new McpServer({
    name: 'justsearch-mcp',
    version: '0.1.0',
  });

  // -------------------------------------------------------------------------
  // Tool: justsearch_answer (registered first — position bias favors QA)
  // -------------------------------------------------------------------------
  mcpServer.registerTool(
    'justsearch_answer',
    {
      description:
        'Get evidence from your indexed documents to answer a question. This is the primary tool ' +
        'for question-answering — it retrieves relevant passages from multiple documents in one call, ' +
        'assembled with source attribution, ready to use as evidence for your answer. ' +
        'Much more efficient than searching and reading documents individually. ' +
        'The response includes facets showing top sources and entities in the index. ' +
        'Use these facet values as filters to scope retrieval: ' +
        'filters: {meta_source: ["the verge"], entity_persons: ["Elon Musk"]}. ' +
        'For questions comparing what different sources report, call this tool once per source ' +
        'with meta_source filters to get source-specific evidence, then synthesize. ' +
        'Use justsearch_search only when you need to explore or discover what is in the index.',
      inputSchema: AnswerInputSchema,
      annotations: { readOnlyHint: true },
    },
    async (rawArgs) => {
      const input = AnswerInputSchema.parse(rawArgs);

      const url = `${baseUrl}/api/knowledge/retrieve-context`;
      const body = {
        query: input.query,
        top_k: input.top_k,
        max_tokens: input.max_tokens,
        auto_entity_extract: input.auto_entity_extract,
        context_format: input.context_format,
      };
      if (input.filters != null) body.filters = input.filters;
      if (input.doc_ids != null && input.doc_ids.length > 0) body.doc_ids = input.doc_ids;
      if (input.return_full_documents) body.return_full_documents = true;

      log(`answer: query="${input.query}" top_k=${input.top_k} max_tokens=${input.max_tokens}`);

      const res = await httpPostJsonLimited(url, body, {
        timeoutMs: 15_000,
        maxBytes: 2_000_000,
      });

      const { parsed, error } = parseJsonResponse(res);
      if (error) return error;

      const quality = parsed.quality ?? {};
      const hints = [];
      if (typeof quality.coverage === 'number' && quality.coverage < 0.5
          && (quality.chunks_included ?? 0) > 0
          && quality.retrieval_mode !== 'FULL_DOCUMENT'
          && quality.retrieval_mode !== 'FULLTEXT_FALLBACK') {
        hints.push('Low context fill ratio — the retrieved passages use less than half the token budget. Try a broader query or remove filters.');
      }
      if ((parsed.total_found ?? 0) === 0 && input.filters != null) {
        hints.push('No passages found with your filters. Try removing or broadening them.');
      }
      // 7b: Guide agents on context_sufficient signal interpretation
      if (quality.context_sufficient === false) {
        hints.push('The retrieved context may not fully answer the question. Consider searching with different terms or broader filters.');
      } else if (quality.context_sufficient === null || quality.context_sufficient === undefined) {
        if ((parsed.total_found ?? 0) > 0) {
          hints.push('Context sufficiency could not be assessed (LLM unavailable). Review the passages carefully before answering.');
        }
      }

      // Exp 2+3: Fetch facets alongside answer for source attribution + entity discovery.
      // Runs a lightweight search (limit=0) purely for facet data. Adds ~50ms, no extra results.
      let answerFacets = null;
      try {
        const facetRes = await httpPostJsonLimited(`${baseUrl}/api/knowledge/search`, {
          query: input.query, limit: 0,
          facets: { include: true, fields: [
            { field: 'meta_source', size: 5 },
            { field: 'entity_persons_raw', size: 5 },
            { field: 'entity_organizations_raw', size: 5 },
          ]},
        }, { timeoutMs: 5_000, maxBytes: 100_000 });
        const facetParsed = facetRes?.statusCode === 200 ? JSON.parse(facetRes.body) : null;
        if (facetParsed?.facets) {
          answerFacets = normalizeEntityFacetKeys(facetParsed.facets);
          // Exp 3: Add entity facet hint on the answer response
          const ep = answerFacets.entity_persons;
          const eo = answerFacets.entity_organizations;
          if ((ep && Object.keys(ep).length > 0) || (eo && Object.keys(eo).length > 0)) {
            const parts = [];
            if (ep) parts.push(`entity_persons: [${Object.keys(ep).slice(0, 2).map(p => `"${p}"`).join(', ')}]`);
            if (eo) parts.push(`entity_organizations: [${Object.keys(eo).slice(0, 2).map(o => `"${o}"`).join(', ')}]`);
            hints.push(`Entities found in the index: pass filters: {${parts.join(', ')}} to scope retrieval to specific people or organizations.`);
          }
          const ms = answerFacets.meta_source;
          if (ms && Object.keys(ms).length > 0) {
            const topSources = Object.keys(ms).slice(0, 3).map(s => `"${s}"`).join(', ');
            hints.push(`Top sources: ${topSources}. For source-specific evidence, add filters: {meta_source: [${topSources.split(', ')[0]}]}.`);
          }
        }
      } catch (e) {
        log(`answer facet sidecar failed: ${e.message}`);
      }

      const result = {
        ok: true,
        context: parsed.context ?? '',
        total_found: parsed.total_found ?? 0,
        chunks: parsed.chunks ?? [],
        quality,
        ...(answerFacets ? { facets: answerFacets } : {}),
        ...(hints.length > 0 ? { hints } : {}),
      };

      // Optional citation verification (absorbs justsearch_match_citations)
      if (input.verify_citations?.answer_text) {
        try {
          const citUrl = `${baseUrl}/api/knowledge/match-citations`;
          const citBody = {
            answer_text: input.verify_citations.answer_text,
            chunk_refs: (parsed.chunks ?? []).map((c) => ({
              parent_doc_id: c.parent_doc_id,
              chunk_index: c.chunk_index,
            })),
            threshold: input.verify_citations.threshold ?? 0.5,
          };
          const citRes = await httpPostJsonLimited(citUrl, citBody, {
            timeoutMs: 10_000,
            maxBytes: 1_000_000,
          });
          const { parsed: citParsed } = parseJsonResponse(citRes);
          if (citParsed) {
            result.citations = {
              matches: citParsed.matches ?? [],
              sentences_total: citParsed.sentences_total ?? 0,
              sentences_matched: citParsed.sentences_matched ?? 0,
            };
          }
        } catch (e) {
          log(`citation verification failed: ${e.message}`);
        }
      }

      return toToolResult(AnswerOutputSchema.parse(result));
    },
  );

  // -------------------------------------------------------------------------
  // Tool: justsearch_search
  // -------------------------------------------------------------------------
  mcpServer.registerTool(
    'justsearch_search',
    {
      description:
        'Find and explore documents in the JustSearch index. Use this to discover what documents ' +
        'exist, browse by source/category/author, or find specific files. Returns file paths, ' +
        'relevance scores, and content previews. For answering questions, prefer justsearch_answer — ' +
        'it retrieves assembled passages from multiple documents in one call. ' +
        'Supports hybrid (default), text (BM25 keyword), and vector (semantic) search modes. ' +
        'For exact phrase or boolean queries, set querySyntax: "LUCENE" with mode: "text". ' +
        'Examples: "Sam Bankman-Fried" AND fraud, title:"climate change" NOT politics. ' +
        'Two filter modes: filters (hard — excludes) and boostFilters (soft — promotes). ' +
        'The system automatically detects sources, authors, and entities in your query and applies ' +
        'soft boosts — check the queryUnderstanding field in the response to see what was detected. ' +
        'Providing explicit filters or boostFilters overrides automatic detection. ' +
        'The first search returns top facet values (sources, categories, authors). ' +
        'Set includeExcerpts: true for query-biased passage excerpts.',
      inputSchema: SearchInputSchema,
      annotations: { readOnlyHint: true },
    },
    async (rawArgs) => {
      const input = SearchInputSchema.parse(rawArgs);

      const url = `${baseUrl}/api/knowledge/search`;
      // Note: Zod defaults (limit=10, mode='hybrid', querySyntax='SIMPLE') are always populated,
      // so these null checks always pass. The MCP server's defaults are authoritative over the backend's.
      const body = { query: input.query };
      if (input.limit != null) body.limit = input.limit;
      if (input.mode != null) body.mode = input.mode;
      if (input.querySyntax != null) body.querySyntax = input.querySyntax;
      if (input.filters != null) body.filters = input.filters;
      if (input.boostFilters != null) body.boostFilters = input.boostFilters;
      if (input.cursor != null) body.cursor = input.cursor;
      if (input.includeExcerpts) body.includeExcerpts = true;

      // Facets: explicit request takes precedence, otherwise auto-include on first page
      if (input.facets != null) {
        if (input.facets === true) {
          body.facets = { include: true, fields: [
            { field: 'meta_source', size: 10 },
            { field: 'meta_category', size: 10 },
            { field: 'meta_author', size: 10 },
            { field: 'entity_persons_raw', size: 10 },
            { field: 'entity_organizations_raw', size: 10 },
            { field: 'entity_locations_raw', size: 10 },
          ]};
        } else {
          body.facets = { include: true, fields: input.facets.fields.map((f) => ({
            field: f, size: input.facets.size ?? 10,
          }))};
        }
      } else if (!input.cursor) {
        // Auto-include on first page for corpus discovery
        body.facets = { include: true, fields: [
          { field: 'meta_source', size: 5 },
          { field: 'meta_category', size: 5 },
          { field: 'meta_author', size: 5 },
          { field: 'entity_persons_raw', size: 5 },
          { field: 'entity_organizations_raw', size: 5 },
          { field: 'entity_locations_raw', size: 5 },
        ]};
      }

      log(`search: query="${input.query}" limit=${input.limit} mode=${input.mode}`);

      const res = await httpPostJsonLimited(url, body, {
        timeoutMs: 15_000,
        maxBytes: 2_000_000,
      });

      const { parsed, error } = parseJsonResponse(res);
      if (error) return error;

      const totalHits = parsed.totalHits ?? 0;
      const hasFilters = input.filters != null && Object.keys(input.filters).length > 0;
      const hasBoostFilters = input.boostFilters != null && Object.keys(input.boostFilters).length > 0;

      // Build response hints (2a + 7b response-level progressive disclosure)
      const hints = [];
      if (totalHits === 0 && hasFilters) {
        hints.push('No results matched your filters. Try removing or broadening them, or use boostFilters instead of filters to promote matches without excluding other documents.');
      } else if (totalHits > 100 && !hasFilters && !hasBoostFilters) {
        const topSources = parsed.facets?.meta_source;
        const sourceHint = topSources
          ? ' Top sources: ' + Object.entries(topSources).slice(0, 3).map(([k, v]) => `${k} (${v})`).join(', ') + '.'
          : '';
        hints.push(`${totalHits} results found. Consider narrowing with filters or boostFilters.${sourceHint}`);
      }

      // Normalize entity facet keys: strip _raw suffix so facet keys match filter param names.
      // Agents see entity_persons in facets → can pass entity_persons in filters directly.
      const facets = normalizeEntityFacetKeys(parsed.facets ?? {});

      // 7b: Response-level hints connecting facet output to filter input.
      // These appear at decision time, not in the tool description that the agent read 10 turns ago.
      const entityPersons = facets.entity_persons;
      const entityOrgs = facets.entity_organizations;
      if ((entityPersons && Object.keys(entityPersons).length > 0)
          || (entityOrgs && Object.keys(entityOrgs).length > 0)) {
        const personExamples = entityPersons
          ? Object.keys(entityPersons).slice(0, 2).map((p) => `"${p}"`).join(', ')
          : '';
        const orgExamples = entityOrgs
          ? Object.keys(entityOrgs).slice(0, 2).map((o) => `"${o}"`).join(', ')
          : '';
        const parts = [];
        if (personExamples) parts.push(`entity_persons: [${personExamples}]`);
        if (orgExamples) parts.push(`entity_organizations: [${orgExamples}]`);
        hints.push(`The facets above show entities found in the results. To filter by them, pass filters: {${parts.join(', ')}}.`);
      }
      if (totalHits > 0 && !input.includeExcerpts) {
        hints.push('Set includeExcerpts: true to get query-highlighted passage excerpts per result.');
      }

      // Echo applied filters (1b)
      const appliedFilters = {};
      if (hasFilters) appliedFilters.filters = input.filters;
      if (hasBoostFilters) appliedFilters.boostFilters = input.boostFilters;

      return toToolResult(
        SearchOutputSchema.parse({
          ok: true,
          totalHits,
          tookMs: parsed.tookMs ?? 0,
          results: (parsed.results ?? []).map((r) =>
            input.verbose ? r : slimSearchResult(r, input.includeProvenance)),
          ...(parsed.nextCursor ? { nextCursor: parsed.nextCursor } : {}),
          ...(Object.keys(facets).length > 0 ? { facets } : {}),
          ...(parsed.entityFacetVariants ? { entityFacetVariants: parsed.entityFacetVariants } : {}),
          // Tempdoc 549 U4 (Slice 6/6b): read correction from the canonical introspection trace
          // (the flat correctionApplied field was removed from the response).
          ...(parsed.introspection?.correction?.applied ? { correctionApplied: true } : {}),
          ...(Object.keys(appliedFilters).length > 0 ? { appliedFilters } : {}),
          ...(hints.length > 0 ? { hints } : {}),
          ...(parsed.queryUnderstanding ? { queryUnderstanding: parsed.queryUnderstanding } : {}),
          ...(parsed.filterNormalization ? { filterNormalization: parsed.filterNormalization } : {}),
        }),
      );
    },
  );

  // -------------------------------------------------------------------------
  // Tool: justsearch_ingest
  // -------------------------------------------------------------------------
  mcpServer.registerTool(
    'justsearch_ingest',
    {
      description:
        'Index files or directories into JustSearch. Provide absolute paths to files or folders. ' +
        'Returns the durable operation response and key. Use justsearch_operation_outcome with that key to check progress.',
      inputSchema: IngestInputSchema,
      annotations: { readOnlyHint: false, destructiveHint: false },
    },
    async (rawArgs) => {
      const input = IngestInputSchema.parse(rawArgs);

      const url = `${baseUrl}/api/knowledge/ingest`;
      const body = {
        paths: input.paths,
        ...(input.collection !== undefined ? { collection: input.collection } : {}),
        ...(input.idempotencyKey !== undefined ? { idempotencyKey: input.idempotencyKey } : {}),
        ...(input.confirmationToken !== undefined ? { confirmationToken: input.confirmationToken } : {}),
        ...(input.preparationNonce !== undefined ? { preparationNonce: input.preparationNonce } : {}),
      };

      log(`ingest: ${input.paths.length} path(s)`);

      const res = await httpPostJsonLimited(url, body, {
        timeoutMs: 30_000,
        maxBytes: 2_000_000,
        headers: { 'X-JustSearch-Transport': 'MCP' },
      });
      if (res.error?.message === 'engine_gone') return engineGoneError(res);

      let operationResponse;
      let parseError = null;
      if (typeof res.text === 'string' && res.error?.message !== 'response_too_large') {
        try {
          operationResponse = JSON.parse(res.text || '');
        } catch {
          parseError = 'Invalid JSON response from backend.';
        }
      }

      const httpSuccess = res.statusCode >= 200 && res.statusCode < 300;
      const succeeded = res.ok === true && httpSuccess && operationResponse?.success === true;
      const operationMessage = typeof operationResponse?.message === 'string'
        ? operationResponse.message
        : null;
      const operationErrorCode = typeof operationResponse?.errorCode === 'string'
        ? operationResponse.errorCode
        : null;
      const errorCode = operationErrorCode
        || (res.error?.message === 'response_too_large'
          ? 'RESPONSE_TOO_LARGE'
          : parseError
            ? 'INVALID_RESPONSE'
            : res.statusCode == null
              ? 'NOT_CONNECTED'
              : 'HTTP_ERROR');
      const errorMessage = operationMessage
        || (res.error?.message === 'response_too_large'
          ? 'Response exceeded size limit.'
          : res.statusCode == null && res.error?.message
            ? 'JustSearch is not running or unreachable. Ensure the desktop app is open.'
            : !httpSuccess
              ? `HTTP ${res.statusCode}`
              : parseError || 'Operation invocation failed');

      return toToolResult(IngestOutputSchema.parse({
        ok: succeeded,
        statusCode: res.statusCode,
        ...(operationResponse !== undefined ? { operationResponse } : {}),
        ...(!succeeded ? {
          error: ToolErrorSchema.parse({ code: errorCode, message: errorMessage }),
        } : {}),
      }));
    },
  );

  // -------------------------------------------------------------------------
  // Tool: justsearch_operation_outcome
  // -------------------------------------------------------------------------
  mcpServer.registerTool(
    'justsearch_operation_outcome',
    {
      description:
        'Read the recorded outcome for an operation key after a lost response or restart. ' +
        'Returns accepted, running, complete, failed, unknown or expired with the retained ' +
        'history boundary and available unit counts. Unknown means no acceptance record and no ' +
        'effect; expired means the retained history cannot answer. This read never starts or retries ' +
        'work. Use the original UUIDv7 operationKey, not executionId. Timestamps are UTC epoch ' +
        'milliseconds. The answer matches GET /api/operation-history/{operationKey}.',
      inputSchema: OperationOutcomeInputSchema,
      annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = OperationOutcomeInputSchema.parse(rawArgs);
      const url = `${baseUrl}/api/operation-history/${encodeURIComponent(input.operationKey)}`;

      log(`operation outcome: ${input.operationKey}`);

      const res = await httpGetTextLimited(url, {
        timeoutMs: 10_000, maxBytes: 500_000, headers: { 'X-JustSearch-Transport': 'MCP' },
      });
      const { parsed, error } = parseJsonResponse(res, { preserveOperationError: true });
      if (error) return { ...error, isError: true };

      // An outcome state of "failed" is still a successful read. Preserve the complete DTO;
      // the operation owner defines its fields and state semantics.
      return toToolResult(parsed);
    },
  );

  // -------------------------------------------------------------------------
  // Tool: justsearch_status
  // -------------------------------------------------------------------------
  mcpServer.registerTool(
    'justsearch_status',
    {
      description:
        'Get the current status of the JustSearch knowledge index. ' +
        'Returns document count, queue depth, readiness state, health, and enrichment coverage ' +
        '(embeddingCoveragePercent, spladeCoveragePercent, pendingNerCount, completedNerCount). ' +
        'After ingesting documents, poll this endpoint to check if enrichment (embeddings, NER, SPLADE) ' +
        'is complete before using entity filters or semantic search.',
      inputSchema: StatusInputSchema,
      annotations: { readOnlyHint: true },
    },
    async () => {
      const url = `${baseUrl}/api/knowledge/status`;

      log('status');

      const res = await httpGetTextLimited(url, { timeoutMs: 10_000, maxBytes: 500_000 });

      const { parsed, error } = parseJsonResponse(res);
      if (error) return error;

      return toToolResult(
        StatusOutputSchema.parse({
          ok: true,
          state: parsed.state,
          ready: parsed.ready,
          docCount: parsed.docCount ?? parsed.indexedDocuments,
          queueDepth: parsed.queueDepth ?? parsed.pendingJobs,
          healthy: parsed.healthy,
          indexState: parsed.indexState,
          embeddingCoveragePercent: parsed.embeddingCoveragePercent ?? undefined,
          spladeCoveragePercent: parsed.spladeCoveragePercent ?? undefined,
          pendingNerCount: parsed.pendingNerCount ?? undefined,
          completedNerCount: parsed.completedNerCount ?? undefined,
        }),
      );
    },
  );

  // -------------------------------------------------------------------------
  // Connect transport
  // -------------------------------------------------------------------------
  const transport = new StdioServerTransport();
  await mcpServer.connect(transport);
  process.stdin.resume(); // paused after discovery; what arrived meanwhile is read first

  log('Server started. Waiting for MCP messages on stdio...');
}
