#!/usr/bin/env node
/**
 * STARTS A REAL DEV STACK. Running this file boots the backend (preflight -> start -> quick_health
 * -> stop) on the shared, one-at-a-time dev stack — it takes the lease, spends about a minute, and
 * can collide with another agent's run. Do NOT run it to "check the tool inventory": that is what
 * `node scripts/ci/check-dev-mcp-doc-sync.mjs` is for, and it needs no stack. Two sessions have
 * broken an explicit no-dev-stack prohibition by reading the old headline ("small local harness to
 * sanity-check the MCP stdio server") as a static check.
 *
 * What it checks (by actually doing it):
 * - stdio framing works (newline-delimited JSON-RPC; no stray stdout)
 * - initialize + tools/list works (12 tools, no dropped tools)
 * - preflight -> start (with integrated wait_ready) -> quick_health (summary + full) -> stop (auto-resolve)
 *
 * The tool-name expectations here are a smoke test, not the inventory authority: the doc-sync gate
 * (scripts/ci/check-dev-mcp-doc-sync.mjs) is what holds the registered set and the reference doc
 * together, and it runs without a dev stack.
 */

import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '..', '..');

const serverPath = path.join(repoRoot, 'scripts', 'dev', 'justsearch-dev-mcp.mjs');

function encodeMessage(obj) {
  const json = JSON.stringify(obj) + '\n';
  return Buffer.from(json, 'utf8');
}

function createLineParser(onMessage) {
  let buf = '';

  const feed = (chunk) => {
    buf += chunk.toString('utf8');
    while (true) {
      const idx = buf.indexOf('\n');
      if (idx === -1) return;
      const line = buf.slice(0, idx).replace(/\r$/, '').trim();
      buf = buf.slice(idx + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch (e) {
        throw new Error(`Protocol violation: stdout line was not JSON. line=${line.slice(0, 500)}`);
      }
      onMessage(msg);
    }
  };

  return { feed };
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function main() {
  const child = spawn(process.execPath, [serverPath], {
    cwd: repoRoot,
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true,
  });

  child.stderr.setEncoding('utf8');
  child.stdout.on('error', () => {});

  let stderrTail = '';
  child.stderr.on('data', (s) => {
    stderrTail += s;
    if (stderrTail.length > 20000) stderrTail = stderrTail.slice(stderrTail.length - 20000);
  });

  const pending = new Map();
  let nextId = 1;

  const parser = createLineParser((msg) => {
    if (msg && typeof msg === 'object' && msg.id != null) {
      const p = pending.get(msg.id);
      if (p) {
        pending.delete(msg.id);
        clearTimeout(p.timer);
        p.resolve(msg);
      }
    }
  });

  child.stdout.on('data', (chunk) => {
    parser.feed(chunk);
  });

  const request = (method, params, timeoutMs = 30_000) => {
    const id = nextId++;
    const payload = { jsonrpc: '2.0', id, method, ...(params !== undefined ? { params } : {}) };
    const buf = encodeMessage(payload);

    const promise = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`Timeout waiting for response to ${method} (id=${id})`));
      }, timeoutMs);
      pending.set(id, { resolve, reject, timer });
    });

    child.stdin.write(buf);
    return promise;
  };

  const notify = (method, params) => {
    const payload = { jsonrpc: '2.0', method, ...(params !== undefined ? { params } : {}) };
    child.stdin.write(encodeMessage(payload));
  };

  let runId = null;
  try {
    // 1) Initialize
    const initRes = await request(
      'initialize',
      {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'justsearch-dev-mcp-harness', version: '0.0.0' },
      },
      10_000,
    );
    if (initRes.error) throw new Error(`initialize failed: ${JSON.stringify(initRes.error)}`);

    notify('notifications/initialized', {});

    const isToolError = (res) => !!res?.error || !!res?.result?.isError;
    const toolErrorText = (res) => {
      const items = res?.result?.content;
      if (!Array.isArray(items) || items.length === 0) return null;
      const first = items[0];
      return typeof first?.text === 'string' ? first.text : null;
    };

    // 2) tools/list
    const listRes = await request('tools/list', {}, 10_000);
    if (listRes.error) throw new Error(`tools/list failed: ${JSON.stringify(listRes.error)}`);
    const toolNames = (listRes.result?.tools || []).map((t) => t.name);
    for (const name of [
      'justsearch.dev.start',
      'justsearch.dev.preflight',
      'justsearch.dev.quick_health',
      'justsearch.dev.acquire_when_free',
      'justsearch.dev.tail_log',
      'justsearch.dev.fetch_api_json',
      'justsearch.dev.api_call',
      'justsearch.dev.search_query',
      'justsearch.dev.ingest',
      'justsearch.dev.stop',
      'justsearch.dev.ai_activate',
      'justsearch.dev.reload',
    ]) {
      if (!toolNames.includes(name)) throw new Error(`Missing tool: ${name}. Got: ${toolNames.join(', ')}`);
    }
    if (toolNames.length !== 12) {
      throw new Error(`Expected 12 tools, got ${toolNames.length}: ${toolNames.join(', ')}`);
    }
    for (const dropped of [
      'justsearch.dev.list_runs',
      'justsearch.dev.wait_ready',
      'justsearch.dev.suggest',
      'justsearch.dev.cleanup',
      // Tempdoc 844 P1 prunes — status folded into quick_health { detail: "full" }; the other
      // three were never invoked in six weeks of transcripts.
      'justsearch.dev.status',
      'justsearch.dev.agent_chat',
      'justsearch.dev.capture_evidence',
      'justsearch.dev.validate_evidence',
    ]) {
      if (toolNames.includes(dropped)) throw new Error(`Dropped tool still present: ${dropped}`);
    }

    // 3) preflight (before start — smoke test)
    const preflightRes = await request(
      'tools/call',
      { name: 'justsearch.dev.preflight', arguments: {} },
      20_000,
    );
    if (isToolError(preflightRes)) {
      throw new Error(
        `tools/call(preflight) failed: ${preflightRes.error ? JSON.stringify(preflightRes.error) : toolErrorText(preflightRes)}`,
      );
    }
    const preflightChecks = preflightRes.result?.structuredContent?.checks;
    // Lane F stage A item A13: was `workerDist` — that check is retired with the Worker
    // distribution it probed; `headDist` is the dist check the preflight result now carries.
    if (!preflightChecks || typeof preflightChecks.headDist !== 'boolean') {
      throw new Error(`preflight returned malformed checks. result=${JSON.stringify(preflightRes.result)?.slice(0, 1200)}`);
    }
    process.stderr.write(`[mcp-harness] preflight: ready=${preflightRes.result?.structuredContent?.ready} checks=${JSON.stringify(preflightChecks)}\n`);

    // 4) start (with integrated wait_ready — waitLevel defaults to ready_worker)
    const startRes = await request(
      'tools/call',
      { name: 'justsearch.dev.start', arguments: { apiPort: 0, uiPort: 5173, clean: 'soft', waitLevel: 'ready_http' } },
      680_000,
    );
    if (isToolError(startRes)) {
      throw new Error(
        `tools/call(start) failed: ${startRes.error ? JSON.stringify(startRes.error) : toolErrorText(startRes)}`,
      );
    }
    runId = startRes.result?.structuredContent?.runId || null;
    if (!runId) {
      throw new Error(`Start did not return runId. result=${JSON.stringify(startRes.result)?.slice(0, 1200)}`);
    }
    const startReadiness = startRes.result?.structuredContent?.readiness;
    const waitReadyTimeout = !!startRes.result?.structuredContent?.waitReadyTimeout;
    if (waitReadyTimeout) {
      process.stderr.write(`[mcp-harness] WARNING: start.waitReadyTimeout=true (readiness=${JSON.stringify(startReadiness)})\n`);
    } else {
      process.stderr.write(`[mcp-harness] start OK: readiness=${JSON.stringify(startReadiness)}\n`);
    }

    // 4.1) quick_health (after start — smoke test)
    const healthRes = await request(
      'tools/call',
      { name: 'justsearch.dev.quick_health', arguments: { probe: true } },
      20_000,
    );
    if (isToolError(healthRes)) {
      throw new Error(
        `tools/call(quick_health) failed: ${healthRes.error ? JSON.stringify(healthRes.error) : toolErrorText(healthRes)}`,
      );
    }
    const healthRunning = healthRes.result?.structuredContent?.running ?? null;
    const healthRunId = healthRes.result?.structuredContent?.runId;
    if (healthRunning === null) {
      process.stderr.write(`[mcp-harness] WARNING: quick_health.running is unknown. result=${JSON.stringify(healthRes.result)?.slice(0, 1200)}\n`);
    } else if (healthRunning === false) {
      process.stderr.write(`[mcp-harness] WARNING: quick_health.running=false. result=${JSON.stringify(healthRes.result)?.slice(0, 1200)}\n`);
    }
    if (healthRunId && healthRunId !== runId) {
      throw new Error(`quick_health runId mismatch. expected=${runId} got=${healthRunId}`);
    }
    process.stderr.write(`[mcp-harness] quick_health: running=${healthRunning} httpReady=${healthRes.result?.structuredContent?.httpReady}\n`);

    // 4.2) quick_health { detail: "full" } — the dev-runner status projection (tempdoc 844 P1:
    // this replaced the retired justsearch.dev.status tool).
    const detailRes = await request(
      'tools/call',
      { name: 'justsearch.dev.quick_health', arguments: { detail: 'full' } },
      25_000,
    );
    if (isToolError(detailRes)) {
      throw new Error(
        `tools/call(quick_health detail=full) failed: ${detailRes.error ? JSON.stringify(detailRes.error) : toolErrorText(detailRes)}`,
      );
    }
    const detail = detailRes.result?.structuredContent?.detail;
    if (!detail) {
      throw new Error(`quick_health(detail=full) returned no detail block. result=${JSON.stringify(detailRes.result)?.slice(0, 1200)}`);
    }
    if (!detail.ok) {
      throw new Error(`quick_health(detail=full).detail returned ok:false. detail=${JSON.stringify(detail)?.slice(0, 1200)}`);
    }
    if (detail.runId !== runId) {
      throw new Error(`quick_health(detail=full).detail runId mismatch. expected=${runId} got=${detail.runId}`);
    }
    process.stderr.write(`[mcp-harness] quick_health(detail=full): ports=${JSON.stringify(detail.ports)}\n`);

    // 4.3) fetch_api_json (effective_config)
    const fetchRes = await request(
      'tools/call',
      { name: 'justsearch.dev.fetch_api_json', arguments: { runId, endpoint: 'effective_config', timeoutMs: 15_000 } },
      25_000,
    );
    if (isToolError(fetchRes)) {
      throw new Error(
        `tools/call(fetch_api_json) failed: ${fetchRes.error ? JSON.stringify(fetchRes.error) : toolErrorText(fetchRes)}`,
      );
    }
    const fetchOk = !!fetchRes.result?.structuredContent?.ok;
    if (!fetchOk) {
      throw new Error(`fetch_api_json returned ok:false. result=${JSON.stringify(fetchRes.result)?.slice(0, 1200)}`);
    }

    // 4.3b) fetch_api_json via apiPort — C3 smoke test (no runId)
    const startApiPort = startRes.result?.structuredContent?.apiPort;
    if (startApiPort) {
      const fetchByPortRes = await request(
        'tools/call',
        { name: 'justsearch.dev.fetch_api_json', arguments: { apiPort: startApiPort, endpoint: 'status' } },
        20_000,
      );
      if (isToolError(fetchByPortRes)) {
        throw new Error(
          `tools/call(fetch_api_json by apiPort) failed: ${fetchByPortRes.error ? JSON.stringify(fetchByPortRes.error) : toolErrorText(fetchByPortRes)}`,
        );
      }
      const fetchByPortOk = !!fetchByPortRes.result?.structuredContent?.ok;
      if (!fetchByPortOk) {
        throw new Error(`fetch_api_json(apiPort) returned ok:false. result=${JSON.stringify(fetchByPortRes.result)?.slice(0, 1200)}`);
      }
      process.stderr.write(`[mcp-harness] fetch_api_json(apiPort=${startApiPort}) OK\n`);
    } else {
      process.stderr.write(`[mcp-harness] WARNING: start did not return apiPort, skipping apiPort smoke test\n`);
    }

    // 4.4) tail_log (backend stdout)
    const tailRes = await request(
      'tools/call',
      { name: 'justsearch.dev.tail_log', arguments: { runId, kind: 'backend_stdout', maxBytes: 20000, maxLines: 200 } },
      20_000,
    );
    if (isToolError(tailRes)) {
      throw new Error(
        `tools/call(tail_log) failed: ${tailRes.error ? JSON.stringify(tailRes.error) : toolErrorText(tailRes)}`,
      );
    }
    const tailOk = !!tailRes.result?.structuredContent?.ok;
    if (!tailOk) {
      throw new Error(`tail_log returned ok:false. result=${JSON.stringify(tailRes.result)?.slice(0, 1200)}`);
    }

    // Negative tests (schema-level):
    // - tail_log rejects unknown kind
    const badTailRes = await request(
      'tools/call',
      // @ts-ignore - intentionally invalid
      { name: 'justsearch.dev.tail_log', arguments: { runId, kind: 'nope' } },
      20_000,
    );
    if (!isToolError(badTailRes)) {
      throw new Error(`Expected tail_log(kind=nope) to error. got=${JSON.stringify(badTailRes)?.slice(0, 1200)}`);
    }
    process.stderr.write(`[mcp-harness] tail_log(kind=nope) rejected: ${toolErrorText(badTailRes) || 'unknown'}\n`);

    // - fetch_api_json rejects unknown endpoint
    const badFetchRes = await request(
      'tools/call',
      // @ts-ignore - intentionally invalid
      { name: 'justsearch.dev.fetch_api_json', arguments: { runId, endpoint: 'nope' } },
      20_000,
    );
    if (!isToolError(badFetchRes)) {
      throw new Error(`Expected fetch_api_json(endpoint=nope) to error. got=${JSON.stringify(badFetchRes)?.slice(0, 1200)}`);
    }
    process.stderr.write(`[mcp-harness] fetch_api_json(endpoint=nope) rejected: ${toolErrorText(badFetchRes) || 'unknown'}\n`);

    // 5) stop (auto-resolve runId + clean:soft to exercise cleanup path)
    const stopRes = await request('tools/call', { name: 'justsearch.dev.stop', arguments: { clean: 'soft' } }, 80_000);
    if (isToolError(stopRes)) {
      throw new Error(`tools/call(stop) failed: ${stopRes.error ? JSON.stringify(stopRes.error) : toolErrorText(stopRes)}`);
    }
    const stopOk = !!stopRes.result?.structuredContent?.ok;
    if (!stopOk) {
      throw new Error(`stop returned ok:false. result=${JSON.stringify(stopRes.result)?.slice(0, 1200)}`);
    }
    const cleanupResult = stopRes.result?.structuredContent?.cleanup;
    if (cleanupResult) {
      process.stderr.write(`[mcp-harness] stop cleanup: ok=${cleanupResult.ok}\n`);
    } else {
      process.stderr.write(`[mcp-harness] WARNING: stop with clean:soft did not return cleanup result\n`);
    }

    process.stderr.write(`[mcp-harness] OK runId=${runId}\n`);
  } catch (err) {
    process.stderr.write(`[mcp-harness] FAILED: ${err?.stack || String(err)}\n`);
    if (runId) {
      try {
        // Best-effort stop if we got a runId.
        await request('tools/call', { name: 'justsearch.dev.stop', arguments: { runId, force: true } }, 60_000);
      } catch (_) {}
    }
    process.stderr.write(`[mcp-harness] server stderr tail:\n${stderrTail}\n`);
    process.exitCode = 1;
  } finally {
    try {
      child.kill();
    } catch (_) {}
    // Give Windows a moment to tear down pipes cleanly.
    await sleep(250);
  }
}

main().catch((err) => {
  process.stderr.write(`[mcp-harness] fatal: ${err?.stack || String(err)}\n`);
  process.exit(1);
});

