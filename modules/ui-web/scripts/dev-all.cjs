#!/usr/bin/env node
/**
 * Dev helper: starts the backend (runHeadless) and Vite together with a clean data dir and a free port.
 * Usage: npm run dev:all
 */
const { spawn } = require("child_process");
const path = require("path");
const process = require("process");
const fs = require("fs");
const net = require("net");
const http = require("http");
const detLib = require("./lib/determinism-budget.cjs");

const rootDir = path.resolve(__dirname, "..", "..", "..");
const uiWebDir = path.resolve(rootDir, "modules", "ui-web");
const gradleCmd = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
const gradlePath = path.resolve(rootDir, gradleCmd);
const basePort = Number(process.env.JUSTSEARCH_API_PORT || 33221);
const dataDir = process.env.JUSTSEARCH_DATA_DIR || path.join(uiWebDir, ".dev-data");

let backend;
let frontend;
let shuttingDown = false;
const det = detLib.createDeterminismBudget({ stdoutSentinel: "JUSTSEARCH_API_PORT=..." });

async function findFreePort(startPort) {
  let port = startPort;
  // try up to 20 ports
  for (let i = 0; i < 20; i += 1) {
    const candidate = port + i;
    const free = await new Promise((resolve) => {
      const srv = net.createServer();
      srv.once("error", () => resolve(false));
      srv.once("listening", () => {
        srv.close(() => resolve(true));
      });
      srv.listen(candidate, "127.0.0.1");
    });
    if (free) return candidate;
  }
  throw new Error("No free port found near " + startPort);
}

function log(prefix, data) {
  process.stdout.write(`[${prefix}] ${data}`);
}

function cleanDataDir(dir) {
  // Preserve user config + Lucene index + watched roots + AI models/packs/policy between dev runs.
  // Note: the API endpoint `/api/indexing/roots` is backed by `watched_roots.json`
  // (see KnowledgeClient / WatchedRootsStore), so if we delete it we can end up with:
  //   - non-zero indexedDocuments (index still present)
  //   - empty roots list in the UI library (roots file gone)
  const keep = new Set([
    "config", "index", "watched_roots.json",
    "models", "installed-packs.v1.json", "policy.v1.json",
  ]);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
    return;
  }
  for (const entry of fs.readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (keep.has(entry)) {
      continue;
    }
    fs.rmSync(full, { recursive: true, force: true });
  }
}

function resolveAiDevEnv() {
  const env = {};

  // Backend scripts historically set these for dev; mirror that here so
  // `npm run dev:all` is a complete single-command workflow.
  if (!process.env.JUSTSEARCH_SERVER_EXE) {
    const winExe = path.join(rootDir, "native-bin", "llama-server", "llama-server.exe");
    const unixExe = path.join(rootDir, "native-bin", "llama-server", "llama-server");
    if (fs.existsSync(winExe)) env.JUSTSEARCH_SERVER_EXE = winExe;
    else if (fs.existsSync(unixExe)) env.JUSTSEARCH_SERVER_EXE = unixExe;
  }

  if (!process.env.JUSTSEARCH_MODELS_DIR) {
    const modelsDir = path.join(rootDir, "models");
    if (fs.existsSync(modelsDir)) env.JUSTSEARCH_MODELS_DIR = modelsDir;
  }

  return env;
}

function checkBackendReadyOnce(port) {
  return new Promise((resolve) => {
    const req = http.request(
      {
        hostname: "127.0.0.1",
        port,
        path: "/api/status",
        method: "GET",
        timeout: 1200,
      },
      (res) => {
        // Drain response to avoid socket hangups.
        res.resume();
        resolve(res.statusCode === 200);
      }
    );
    req.on("timeout", () => {
      req.destroy(new Error("timeout"));
    });
    req.on("error", () => resolve(false));
    req.end();
  });
}

async function waitForBackendReady(port, timeoutMs) {
  return await detLib.waitUntil(() => checkBackendReadyOnce(port), {
    det,
    reason: "wait_for_backend_ready",
    deadlineMs: timeoutMs,
    intervalMs: 500,
  });
}

function killProcessTreeWindows(pid) {
  if (!pid) return;
  try {
    // Best-effort. `/T` kills the process tree so the worker doesn't linger.
    spawn("taskkill", ["/PID", String(pid), "/T", "/F"], {
      stdio: "ignore",
      windowsHide: true,
      shell: false,
    });
  } catch (_) {
    // ignore
  }
}

function terminateChild(proc) {
  if (!proc) return;
  if (proc.exitCode != null) return;

  if (process.platform === "win32") {
    killProcessTreeWindows(proc.pid);
    return;
  }

  try {
    proc.kill("SIGTERM");
  } catch (_) {
    // ignore
  }

  // Escalate if needed.
  setTimeout(() => {
    try {
      if (proc.exitCode == null) proc.kill("SIGKILL");
    } catch (_) {
      // ignore
    }
  }, 2500);
}

async function startBackend(port) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finishOk = () => {
      if (settled) return;
      settled = true;
      resolve();
    };
    const finishErr = (err) => {
      if (settled) return;
      settled = true;
      reject(err);
    };

    const aiEnv = resolveAiDevEnv();
    backend = spawn(
      gradlePath,
      ["--no-daemon", "--no-configuration-cache", "-PskipWebBuild=true", ":modules:ui:runHeadless"],
      {
        cwd: rootDir,
        env: {
          ...process.env,
          ...aiEnv,
          JUSTSEARCH_API_PORT: String(port),
          JUSTSEARCH_DATA_DIR: dataDir,
        },
        shell: process.platform === "win32",
      }
    );

    backend.stdout.on("data", (data) => {
      log("api", data);
    });
    backend.stderr.on("data", (data) => log("api", data));

    backend.on("exit", (code) => {
      if (!frontend) {
        finishErr(new Error(`Backend exited with code ${code}`));
        return;
      }
      shutdown();
      process.exit(code || 0);
    });

    // Deterministic readiness: poll /api/status instead of waiting on a log line.
    (async () => {
      try {
        const ok = await waitForBackendReady(port, 60_000);
        if (!ok) {
          terminateChild(backend);
          finishErr(new Error(`Backend did not become ready at http://127.0.0.1:${port}/api/status within 60s`));
          return;
        }
        finishOk();
      } catch (err) {
        terminateChild(backend);
        finishErr(err);
      }
    })();
  });
}

function startFrontend(port) {
  frontend = spawn("npm", ["run", "dev", "--", "--host", "--port", "5173", "--strictPort"], {
    cwd: uiWebDir,
    env: {
      ...process.env,
      // Frontend discovery uses VITE_JUSTSEARCH_API_PORT (resolveApiEndpoint()).
      VITE_JUSTSEARCH_API_PORT: String(port),
      // Vite proxy currently reads VITE_API_PORT; keep for back-compat until vite.config.js is aligned.
      VITE_API_PORT: String(port),
    },
    shell: process.platform === "win32",
    stdio: "inherit",
  });

  frontend.on("exit", (code) => {
    shutdown();
    process.exit(code || 0);
  });
}

function shutdown() {
  if (shuttingDown) return;
  shuttingDown = true;

  terminateChild(frontend);
  terminateChild(backend);
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

(async () => {
  try {
    const port = await findFreePort(basePort);
    cleanDataDir(dataDir);
    await startBackend(port);

    console.log("");
    console.log("========================================");
    console.log(` Frontend: http://localhost:5173`);
    console.log(` Backend:  http://127.0.0.1:${port}/api/status`);
    console.log(` Debug:    http://127.0.0.1:${port}/api/debug/dashboard`);
    console.log("========================================");
    console.log("");

    startFrontend(port);
  } catch (err) {
    console.error("Failed to start dev stack", err);
    shutdown();
    process.exit(1);
  }
})();
