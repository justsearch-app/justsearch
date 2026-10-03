#!/usr/bin/env node

import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { parseArgs } from 'node:util';

const { values } = parseArgs({
  options: {
    'scenario-file': { type: 'string' },
    'request-log': { type: 'string' },
    'ready-file': { type: 'string' },
    port: { type: 'string', default: '0' },
  },
  strict: true,
});

if (!values['scenario-file']) {
  process.stderr.write('Error: --scenario-file is required\n');
  process.exit(2);
}
if (!values['request-log']) {
  process.stderr.write('Error: --request-log is required\n');
  process.exit(2);
}
if (!values['ready-file']) {
  process.stderr.write('Error: --ready-file is required\n');
  process.exit(2);
}

const scenarioPath = path.resolve(values['scenario-file']);
const requestLogPath = path.resolve(values['request-log']);
const readyFilePath = path.resolve(values['ready-file']);
const requestedPort = Number.parseInt(values.port, 10) || 0;

const scenario = JSON.parse(fs.readFileSync(scenarioPath, 'utf8').replace(/^\uFEFF/, ''));
const requestLog = [];
const counters = new Map();

function ensureParentDir(filePath) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
}

function writeJson(filePath, value) {
  ensureParentDir(filePath);
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function baseStatus(overrides = {}) {
  return {
    indexAvailable: true,
    indexHealthy: true,
    indexState: 'IDLE',
    pendingJobs: 0,
    pendingJobsCount: 0,
    processingJobsCount: 0,
    pendingReadyJobsCount: 0,
    pendingBackoffJobsCount: 0,
    buildingIndexedDocuments: 0,
    indexedDocuments: 0,
    chunkVectorsReady: false,
    spladeDocCount: 0,
    spladePendingCount: 0,
    spladeFailedCount: 0,
    spladeCoveragePercent: 0,
    readiness: {
      composites: {
        retrieval: {
          state: 'READY',
        },
      },
      components: {
        chunkEmbedding: {
          state: 'READY',
        },
      },
    },
    ...overrides,
  };
}

function baseDebugState(overrides = {}) {
  return {
    reranking: {
      lambdamart: {
        active: false,
        training: {
          status: 'PENDING',
        },
      },
    },
    ...overrides,
  };
}

function getSequence(name) {
  if (Array.isArray(scenario[name])) {
    return scenario[name];
  }
  return null;
}

function takeSequenceEntry(name, defaultValue = null) {
  const seq = getSequence(name);
  if (!seq || seq.length === 0) {
    return defaultValue;
  }
  const index = counters.get(name) ?? 0;
  counters.set(name, index + 1);
  return seq[Math.min(index, seq.length - 1)];
}

function normalizeResponse(entry, defaultStatusCode, defaultBody) {
  if (entry === null || typeof entry === 'undefined') {
    return { statusCode: defaultStatusCode, body: defaultBody };
  }
  if (
    typeof entry === 'object'
    && entry !== null
    && (Object.prototype.hasOwnProperty.call(entry, 'statusCode')
      || Object.prototype.hasOwnProperty.call(entry, 'body')
      || Object.prototype.hasOwnProperty.call(entry, 'rawBody')
      || Object.prototype.hasOwnProperty.call(entry, 'headers'))
  ) {
    return {
      statusCode: Number.isInteger(entry.statusCode) ? entry.statusCode : defaultStatusCode,
      body: Object.prototype.hasOwnProperty.call(entry, 'body') ? entry.body : defaultBody,
      rawBody: entry.rawBody,
      headers: entry.headers ?? {},
    };
  }
  return { statusCode: defaultStatusCode, body: entry };
}

function appendRequest(entry) {
  requestLog.push(entry);
  writeJson(requestLogPath, requestLog);
}

function readRequestBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      const buffer = Buffer.concat(chunks);
      const text = buffer.toString('utf8');
      if (!text) {
        resolve({ raw: '', json: null });
        return;
      }
      try {
        resolve({ raw: text, json: JSON.parse(text) });
      } catch (error) {
        reject(error);
      }
    });
    req.on('error', reject);
  });
}

function inferSearchMode(body) {
  if (body && typeof body.mode === 'string') return body.mode;
  // When the eval script sends a pipeline config instead of a mode string,
  // infer mode from the pipeline shape so searchByMode dispatch still works.
  if (body?.pipeline && typeof body.pipeline === 'object') {
    return body.pipeline.denseEnabled ? 'hybrid' : 'lexical';
  }
  return null;
}

function getSearchResponse(body) {
  const mode = inferSearchMode(body);
  if (scenario.searchByMode && mode) {
    if (Object.prototype.hasOwnProperty.call(scenario.searchByMode, mode)) {
      return normalizeResponse(scenario.searchByMode[mode], 200, {
        results: [],
        tookMs: 1,
        totalHits: 0,
        effectiveMode: String(mode).toUpperCase(),
      });
    }
  }
  return normalizeResponse(takeSequenceEntry('searchSequence'), 200, {
    results: [],
    tookMs: 1,
    totalHits: 0,
    effectiveMode: mode ? String(mode).toUpperCase() : 'UNKNOWN',
  });
}

async function handleRequest(req, res) {
  const requestBody = await readRequestBody(req).catch((error) => {
    res.writeHead(400, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: `invalid_json:${error.message}` }));
    return null;
  });
  if (requestBody === null) {
    return;
  }

  const url = new URL(req.url, 'http://127.0.0.1');
  appendRequest({
    method: req.method,
    path: url.pathname,
    query: Object.fromEntries(url.searchParams.entries()),
    body: requestBody.json,
  });

  let response;
  if (req.method === 'GET' && url.pathname === '/api/status') {
    const statusEntry = takeSequenceEntry('statusSequence', scenario.defaultStatus);
    response = normalizeResponse(statusEntry, 200, baseStatus());
    response.body = baseStatus(response.body ?? {});
  } else if (req.method === 'GET' && url.pathname === '/api/debug/state') {
    const debugEntry = takeSequenceEntry('debugStateSequence', scenario.defaultDebugState);
    response = normalizeResponse(debugEntry, 200, baseDebugState());
    response.body = baseDebugState(response.body ?? {});
  } else if (req.method === 'POST' && url.pathname === '/api/indexing/roots') {
    response = normalizeResponse(takeSequenceEntry('indexRootsSequence'), 200, { ok: true });
  } else if (req.method === 'POST' && url.pathname === '/api/knowledge/ingest') {
    response = normalizeResponse(takeSequenceEntry('ingestSequence'), 200, {
      success: true, message: 'Ingestion accepted',
      structuredData: { operationKey: '01996c43-8300-7000-8000-000000000001', status: 'ACCEPTED' },
    });
  } else if (req.method === 'POST' && url.pathname === '/api/knowledge/search') {
    response = getSearchResponse(requestBody.json);
  } else {
    response = { statusCode: 404, body: { error: 'not_found' }, headers: {} };
  }

  const headers = {
    'content-type': 'application/json',
    ...(response.headers ?? {}),
  };
  res.writeHead(response.statusCode, headers);
  if (typeof response.rawBody === 'string') {
    res.end(response.rawBody);
    return;
  }
  res.end(JSON.stringify(response.body ?? {}));
}

const server = http.createServer((req, res) => {
  handleRequest(req, res).catch((error) => {
    res.writeHead(500, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: `server_error:${error.message}` }));
  });
});

server.listen(requestedPort, '127.0.0.1', () => {
  const address = server.address();
  writeJson(readyFilePath, { port: address.port });
  process.stdout.write(JSON.stringify({ ok: true, port: address.port }) + '\n');
});

function shutdown() {
  server.close(() => process.exit(0));
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
